"""Composition of runtime capabilities; concrete adapters stay at this boundary."""

import os
from dataclasses import dataclass
from functools import partial

from langchain_core.runnables import RunnableLambda
from langgraph.checkpoint.memory import InMemorySaver

from pycode.agent import RunContext
from pycode.agent.adapters.agent import AgentRuntime
from pycode.agent.adapters.bindings import RuntimeBindings
from pycode.agent.adapters.boundaries import AgentMessagePublisher
from pycode.app.composition.capabilities import (
    build_backend,
    build_hook_actions,
    build_mcp_bindings,
    build_output_store,
    build_python_skill_factory,
    build_sandbox,
    build_skills,
    default_tools,
    skill_catalog,
    skill_sources,
    skill_tools,
    worker_sandbox,
)
from pycode.app.composition.context import agents_md_sources, build_summary_generator
from pycode.app.composition.models import build_background_model
from pycode.app.config import AppConfig, credential_environment
from pycode.app.team_runtime import TeamDelegate
from pycode.context import (
    ContextProjector,
    EvictionBudget,
    InputBudget,
    SummaryService,
    SummarySettings,
    ToolEvictionService,
)
from pycode.context.adapters.storage.prompts import (
    InstructionFileBackend,
)
from pycode.context.adapters.storage.summaries import FileSummaryStore
from pycode.context.adapters.summary import prepare_summary
from pycode.hooks import HookEngine, ToolHookBridge, run_hook
from pycode.kernel import (
    EventConsumer,
    EventJournal,
    EventStream,
    MemoryEventJournal,
    MessageJournal,
    Purpose,
    SessionTree,
    SystemTiming,
    ToolSpec,
    estimate_text,
    plain_data,
)
from pycode.kernel import ExecutableTool as Tool
from pycode.mcp import result_from_mcp
from pycode.memory.adapters.memory_tools import memory_tools
from pycode.models import ProviderConfig, RetryPolicy, model_selection, selector_provider
from pycode.models.adapters.calibration import calibrate_model_request
from pycode.models.adapters.factory import ModelFactory
from pycode.models.adapters.failures import ProviderFailureClassifier
from pycode.models.adapters.messages import from_assistant, to_framework
from pycode.models.adapters.metered_model import MeteredChatModel
from pycode.models.adapters.model_fallback import ModelCandidate, ModelFallback
from pycode.models.adapters.model_requests import ModelRequestRunner, model_request_step
from pycode.models.adapters.observation import ProviderObservation
from pycode.models.adapters.storage.requests import SQLiteRequestStore
from pycode.models.adapters.tool_selection import ToolSelector, select_model_tools
from pycode.sandbox.adapters.tools.bash import find_bash
from pycode.session import DurableInputs, PendingInputs, SessionStore, assign_initial_title
from pycode.session.adapters.storage.layout import StorageLayout
from pycode.skills import SkillLoadRecorder, SkillResources, SkillSelection
from pycode.skills.adapters.skills import PycodeSkillsMiddleware
from pycode.snapshot.adapters.git_tree import GitSnapshotStore
from pycode.team.adapters.team_tools import child_tools, lead_tools
from pycode.tools import (
    ConfiguredActionGate,
    MemoryToolGate,
    NoOpToolGate,
    PlanToolGate,
    ReadOnlyToolGate,
    SkillToolGate,
    ToolExecutor,
    ToolRegistry,
    WorkerBoundaryGate,
)
from pycode.tools.adapters.backend import PycodeBackend
from pycode.tools.adapters.framework_tool import make_framework_tool
from pycode.tools.adapters.permissions import InterruptToolGate, TeamToolGate
from pycode.tools.adapters.questions import InterruptQuestionPrompt
from pycode.tools.adapters.schema import JsonSchemaValidator
from pycode.tools.adapters.settlement import ToolSettlementMiddleware
from pycode.tools.adapters.tool_errors import execute_tool_errors


@dataclass
class RuntimeContainer:
    runtime: AgentRuntime
    context: RunContext
    request_store: SQLiteRequestStore
    model_factory: ModelFactory
    owns_summary: bool = True
    event_store: MemoryEventJournal | None = None
    owns_hooks: bool = True

    async def aclose(self) -> None:
        try:
            try:
                if self.owns_hooks and self.context.hooks is not None:
                    await self.context.hooks.aclose()
            finally:
                try:
                    if self.owns_summary and self.context.summary is not None:
                        await self.context.summary.aclose()
                finally:
                    if self.context.model_fallback is not None:
                        await self.context.model_fallback.aclose()
        finally:
            try:
                await self.model_factory.aclose()
            finally:
                try:
                    await self.request_store.aclose()
                finally:
                    if self.event_store is not None:
                        await self.event_store.aclose()


def build_runtime(
    config: AppConfig,
    provider: ProviderConfig,
    *,
    session_id: str,
    run_id: str,
    journal: MessageJournal,
    tools: tuple[Tool, ...],
    consumers: tuple[EventConsumer, ...],
    checkpointer=None,
    retry_policy: RetryPolicy | None = None,
    agent_id: str = "lead",
    purpose: Purpose = "agent",
    history_store: SessionStore | None = None,
    eviction_budget: EvictionBudget | None = None,
    summary_service: SummaryService | None = None,
    event_journal: EventJournal | None = None,
    configured_hooks=(),
    owned_hook_tasks=None,
    mcp_bindings=None,
    mcp_config_hash="",
    plans=None,
    plan_backend=None,
    durable_inputs=None,
    effective_tools=(),
    skill_selection=None,
    model_resolver=None,
    memory=None,
    team_member=None,
) -> RuntimeContainer:
    checkpointer = checkpointer if checkpointer is not None else InMemorySaver()
    observation = ProviderObservation(provider.implementation)
    factory = ModelFactory(credential_environment(config, (provider,)), observation)
    model = factory.create(provider)
    validator = JsonSchemaValidator()
    scoped_memory_tools = memory_tools(memory, read_only=config.read_only)
    team_tools = ()
    if team_member is not None:
        team_tools = (
            lead_tools(team_member, provider)
            if team_member.agent_id == "lead"
            else child_tools(team_member)
        )
    tools = (*tools, *scoped_memory_tools, *team_tools)
    layout = StorageLayout(config.workspace, config.user_data)
    outputs = build_output_store(layout.sessions, session_id, history_store)
    enabled_tools = (
        (
            *tools,
            *(skill_tools(config, history_store) if history_store is not None else ()),
            *default_tools(
                config,
                outputs,
                find_bash(),
                InterruptQuestionPrompt()
                if checkpointer is not None and config.agent_id == "lead"
                else None,
                plans,
            ),
        )
        if provider.supports_tools
        else ()
    )
    if config.read_only:
        enabled_tools = tuple(tool for tool in enabled_tools if tool.spec.category == "read")
    registry = ToolRegistry(validator, enabled_tools)
    event_store = MemoryEventJournal() if event_journal is None else None
    events = EventStream(session_id, run_id, consumers, journal=event_journal or event_store)
    request_store = SQLiteRequestStore(
        config.request_database
        or StorageLayout(config.workspace, config.user_data).runtime_database
    )
    timing = SystemTiming()
    requests = ModelRequestRunner(
        request_store,
        ProviderFailureClassifier(timing),
        timing,
        retry_policy or RetryPolicy(),
        events,
        factory.selection(provider),
        session_id,
        run_id,
        observation=observation,
        agent_id=config.agent_id if agent_id == "lead" else agent_id,
        purpose="subagent" if config.agent_id != "lead" and purpose == "agent" else purpose,
    )
    gate = (
        InterruptToolGate(build_sandbox(), config.sandbox)
        if checkpointer is not None and not config.read_only
        else ReadOnlyToolGate()
    )
    if scoped_memory_tools:
        gate = MemoryToolGate(gate, tuple(tool.spec for tool in scoped_memory_tools))
    if plans is not None:
        gate = PlanToolGate(plans, gate)
    if config.tool_gate is not None:
        gate = config.tool_gate
    if history_store is not None:
        gate = SkillToolGate(history_store, gate)
    constraints = (
        SkillToolGate(history_store, config.tool_gate or NoOpToolGate())
        if history_store is not None
        else (config.tool_gate or NoOpToolGate())
    )
    if config.agent_id != "lead":
        strict_sandbox = worker_sandbox(config)
        gate = WorkerBoundaryGate(
            constraints,
            InterruptToolGate(strict_sandbox, config.sandbox),
            readonly=config.read_only,
            sandbox=strict_sandbox if config.isolated_worker else None,
            settings=config.sandbox,
        )
    elif team_member is not None:
        gate = TeamToolGate(team_member.team, gate, constraints, plans)
    snapshots = GitSnapshotStore(
        config.workspace, layout.project / "snapshots", excluded_roots=(config.user_data,)
    )
    hooks = None
    if configured_hooks and history_store is not None:
        hooks = build_hook_engine(
            configured_hooks,
            history_store,
            build_hook_actions(
                outputs,
                find_bash(),
                build_sandbox(),
                config.sandbox,
                credential_environment(config, (provider,)),
                delegate=TeamDelegate(team_member.team, provider, history_store, run_id)
                if config.allow_delegation and team_member is not None
                else None,
            ),
            validator,
            outputs,
            events,
            snapshots=snapshots,
            owned_tasks=owned_hook_tasks,
            plans=plans,
            read_only=config.read_only,
            error_boundary=execute_tool_errors,
        )
    executor = ToolExecutor(
        registry,
        validator,
        gate,
        events,
        outputs,
        snapshots=snapshots,
        hooks=ToolHookBridge(hooks) if hooks is not None else None,
        error_boundary=execute_tool_errors,
    )
    budget = InputBudget.create(provider.context_window, provider.max_output_tokens)
    window = budget.input_limit
    eviction = (
        ToolEvictionService(
            history_store, ContextProjector(), eviction_budget or EvictionBudget(window), events
        )
        if history_store is not None
        else None
    )
    workspace_files = PycodeBackend(
        config.workspace,
        read_only=config.read_only,
        excluded_roots=(config.user_data,),
    )
    context = RunContext(
        session_id=session_id,
        run_id=run_id,
        workspace=str(config.workspace),
        selection=factory.selection(provider),
        events=events,
        journal=journal,
        executor=executor,
        registry=registry,
        inputs=PendingInputs(),
        requests=requests,
        history_store=history_store,
        projector=ContextProjector() if history_store is not None else None,
        outputs=outputs,
        eviction=eviction,
        durable_inputs=durable_inputs or (DurableInputs(history_store) if history_store else None),
        agent_id=config.agent_id if agent_id == "lead" else agent_id,
        hooks=hooks,
        mcp=mcp_bindings,
        mcp_config_hash=mcp_config_hash,
        plans=plans,
        workspace_files=workspace_files,
        model_tools=effective_tools,
        skill_selection=skill_selection,
        read_only=config.read_only,
        team=team_member,
    )
    if provider.supports_tools:

        def selection_model(current, boundary, validate_response):
            selected = selector_provider(provider, model_resolver)
            limit = min(
                provider.tool_selector.input_limit,
                InputBudget.create(selected.context_window, selected.max_output_tokens).input_limit,
            )
            owner = build_background_model(
                config, selected, consumers, current.session_id, current.run_id, "tool_selection"
            )
            owner.model = MeteredChatModel(
                model=owner.model,
                requests=owner.requests,
                logical_prefix=boundary + ":tool_selection",
                input_limit=limit,
                response_validator=validate_response,
            )
            return owner

        context.tool_selector = ToolSelector(provider.tool_selector, selection_model)
    if history_store is not None and provider.supports_tools:
        catalog = skill_catalog(config)
        context.skill_recorder = SkillLoadRecorder(history_store, catalog)
    specs = registry.list_specs()
    if history_store is not None:
        settings = SummarySettings(
            factory.selection(provider),
            effective_tools or specs,
            budget,
            config.summary_keep_recent_tokens,
        )
        generator = build_summary_generator(config, provider, consumers)
        if summary_service is None:
            summary_service = SummaryService(
                history_store,
                ContextProjector(),
                FileSummaryStore(layout.project / "summaries"),
                generator,
                settings,
                events,
                preparation=prepare_summary,
                on_compacted=memory.compacted if memory is not None else None,
            )
        else:
            summary_service.configure(settings, generator, events)
        context.summary = summary_service
    if provider.fallback_models and config.agent_id == "lead" and purpose == "agent":

        def fallback_owner(selected, current, index):
            return build_background_model(
                config,
                selected,
                consumers,
                current.session_id,
                current.run_id,
                purpose,
                retry_policy=retry_policy,
                candidate_index=index,
            )

        def configure_candidate(current, selected):
            changed = current.selection != model_selection(selected)
            current.selection = model_selection(selected)
            if current.summary is not None:
                settings = SummarySettings(
                    current.selection,
                    current.model_tools or current.registry.model_specs(),
                    InputBudget.create(selected.context_window, selected.max_output_tokens),
                    config.summary_keep_recent_tokens,
                )
                current.summary.configure(
                    settings, build_summary_generator(config, selected, consumers), current.events
                )
            if changed:
                current.system_hash = ""

        context.model_fallback = ModelFallback(
            ModelCandidate(model, provider, requests, 0),
            provider.fallback_models,
            model_resolver,
            fallback_owner,
            configure_candidate,
            calibrator=partial(
                calibrate_model_request, converter=to_framework, estimate_text=estimate_text
            ),
        )
    instructions = agents_md_sources(config)
    runtime = build_agent_runtime(
        model,
        title_assignment=BoundTitleAssignment(history_store) if history_store is not None else None,
        checkpointer=checkpointer,
        backend=build_backend(
            config,
            plan_backend=plan_backend,
            workspace_files=workspace_files,
            outputs=outputs,
            history=history_store,
            skill_selection=skill_selection,
        ),
        skills=skill_sources(config),
        subagents=(),
        read_only=config.read_only,
        instructions=instructions,
        instruction_backend=InstructionFileBackend(instructions),
    )
    return RuntimeContainer(
        runtime,
        context,
        request_store,
        factory,
        event_store=event_store,
    )


def fork_skill_specs(config, provider, history, consumers, run_id, entries, catalog, team_member):
    specs = []
    for entry in entries:
        if entry.mode != "fork":
            continue
        delegate = TeamDelegate(team_member.team, provider, history, run_id)
        delegate.skill = entry
        delegate.skill_path = catalog.virtual_path(entry)
        specs.append(
            {
                "name": "skill-" + entry.name,
                "description": entry.description,
                "mode": "fork",
                "runnable": RunnableLambda(delegate.invoke),
            }
        )
    return specs


def build_lifecycle_hook_engine(launcher, store, snapshot, lifetime):
    layout = StorageLayout(launcher.config.workspace, launcher.config.user_data)
    events = EventStream(
        snapshot.header.session_id, lifetime, launcher.consumers, journal=launcher.event_journal
    )
    outputs = build_output_store(layout.sessions, snapshot.header.session_id, store)
    engine = build_hook_engine(
        launcher.configured_hooks,
        store,
        build_hook_actions(
            outputs,
            find_bash(),
            build_sandbox(),
            launcher.config.sandbox,
            dict(os.environ),
            delegate=TeamDelegate(
                launcher.team_member.team, launcher.lifecycle_provider, store, lifetime
            )
            if launcher.config.allow_delegation and launcher.team_member is not None
            else None,
        ),
        JsonSchemaValidator(),
        outputs,
        events,
        snapshots=GitSnapshotStore(
            launcher.config.workspace,
            layout.project / "snapshots",
            excluded_roots=(launcher.config.user_data,),
        ),
        owned_tasks=launcher.hook_tasks,
        plans=launcher.plans,
        read_only=launcher.config.read_only,
        error_boundary=execute_tool_errors,
    )
    return engine


async def build_configured_run(launcher, provider, store, run_id):
    snapshot = await store.snapshot()
    catalog = skill_catalog(launcher.config)
    index = await catalog.scan()
    indexed = await build_skills(launcher.config, store).refresh(index)
    paths = []
    for entry in index.entries:
        paths.append(catalog.virtual_path(entry))
    selection = SkillSelection(indexed["dispatch"], tuple(paths))
    for entry in SessionTree(snapshot.index).path(snapshot.leaf_id):
        if entry.message is not None and entry.message.role == "user":
            requested = entry.message.provider_metadata.get("skill_request", {})
            path = requested.get("path")
            if path in paths:
                selection.select((path,))
    projected = ContextProjector().project(snapshot)
    inherited_tools = []
    for declaration in projected.checkpoint.get("model_tools", ()):
        inherited_tools.append(ToolSpec(**plain_data(declaration)))
    container = build_runtime(
        launcher.config,
        provider,
        session_id=snapshot.header.session_id,
        run_id=run_id,
        journal=store,
        tools=launcher.tools,
        memory=launcher.memory,
        team_member=launcher.team_member,
        consumers=launcher.consumers,
        history_store=store,
        checkpointer=InMemorySaver(),
        summary_service=launcher.summary,
        model_resolver=launcher.model_resolver,
        event_journal=launcher.event_journal,
        configured_hooks=launcher.configured_hooks,
        owned_hook_tasks=launcher.hook_tasks,
        mcp_bindings=build_mcp_bindings(store, launcher.mcp_connections, provider)
        if launcher.mcp_connections is not None and provider.supports_tools
        else None,
        mcp_config_hash=launcher.mcp_config_hash,
        plans=launcher.plans,
        plan_backend=launcher.plan_backend,
        durable_inputs=launcher.durable_inputs,
        effective_tools=tuple(inherited_tools),
        skill_selection=selection,
    )
    if launcher.config.allow_delegation and launcher.team_member is not None:
        container.runtime.subagents = (
            *container.runtime.subagents,
            *fork_skill_specs(
                launcher.config,
                provider,
                store,
                launcher.consumers,
                run_id,
                index.entries,
                catalog,
                launcher.team_member,
            ),
        )
    container.context.skill_resources = SkillResources(
        catalog,
        build_python_skill_factory(
            catalog,
            container.context.outputs,
            worker_sandbox(launcher.config),
            launcher.config.sandbox,
        ),
        index.entries,
        reserved_tools=(
            *(spec.name for spec in container.context.registry.list_specs()),
            "read_file",
            "write_file",
            "edit_file",
            "ls",
            "glob",
            "grep",
            "execute",
            "write_todos",
            "task",
        ),
        history=store,
    )
    return container


def build_hook_engine(hooks, history, actions, validator, outputs, events, **kwargs):
    def executor_factory(tools, *, plans=None, read_only=False):
        return ToolExecutor(
            ToolRegistry(validator, tools),
            validator,
            ConfiguredActionGate(plans, read_only),
            events,
            outputs,
            error_boundary=kwargs["error_boundary"],
            snapshots=kwargs.get("snapshots"),
        )

    return HookEngine(
        hooks,
        history,
        actions,
        validator,
        outputs,
        events,
        executor_factory=executor_factory,
        **kwargs,
    )


def build_runtime_bindings():
    calibrator = partial(
        calibrate_model_request, converter=to_framework, estimate_text=estimate_text
    )
    return RuntimeBindings(
        to_framework=to_framework,
        from_assistant=from_assistant,
        make_framework_tool=make_framework_tool,
        select_model_tools=select_model_tools,
        calibrate_model_request=calibrator,
        model_request_step=model_request_step,
        skills_middleware_factory=PycodeSkillsMiddleware,
        settlement_middleware_factory=lambda context: ToolSettlementMiddleware(
            publisher=AgentMessagePublisher(context),
            lifecycle=lambda _context, event, boundary, **details: run_hook(
                context, event, boundary, **details
            ),
            converter=to_framework,
            result_normalizer=result_from_mcp,
        ),
        run_hook=run_hook,
    )


def build_agent_runtime(model, **kwargs):
    return AgentRuntime(model, bindings=build_runtime_bindings(), **kwargs)


class BoundTitleAssignment:
    def __init__(self, history):
        self.history = history

    async def assign(self, message):
        await assign_initial_title(self.history, message)
