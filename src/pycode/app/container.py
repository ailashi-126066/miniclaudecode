"""The sole composition root: concrete dependencies are assembled explicitly."""

import asyncio
import hashlib
import os
import sys
from dataclasses import dataclass, replace
from pathlib import Path

from deepagents.backends import CompositeBackend, StateBackend
from langchain_core.runnables import RunnableLambda
from langgraph.checkpoint.memory import InMemorySaver

from pycode import __version__
from pycode.adapters.hooks.actions import ConfiguredHookActions
from pycode.adapters.hooks.config import load_hooks
from pycode.adapters.mcp.config import load_servers
from pycode.adapters.mcp.manager import MCPAdapterConnections
from pycode.adapters.mcp.tools import (
    MCPCall,
    MCPGetPrompt,
    MCPPrompts,
    MCPReadResource,
    MCPResources,
    MCPTool,
    ToolSearch,
)
from pycode.adapters.models.directory import SDKModelDirectory
from pycode.adapters.models.factory import ModelFactory
from pycode.adapters.models.failures import ProviderFailureClassifier
from pycode.adapters.models.observation import ProviderObservation
from pycode.adapters.sandbox.seatbelt import SeatbeltSandbox, UnavailableSandbox
from pycode.adapters.skills.backend import SkillBackend
from pycode.adapters.skills.catalog import FileSkillCatalog
from pycode.adapters.skills.install import GitHubSkillInstaller
from pycode.adapters.skills.python_tool import PythonSkillToolFactory
from pycode.adapters.snapshot.git_tree import GitSnapshotStore
from pycode.adapters.storage.catalog import FileCatalogStore
from pycode.adapters.storage.history_backend import ConversationHistoryBackend
from pycode.adapters.storage.layout import StorageLayout
from pycode.adapters.storage.objects import FileStorageObjects
from pycode.adapters.storage.plans import FilePlanStore
from pycode.adapters.storage.prompts import ProjectPromptSource
from pycode.adapters.storage.requests import SQLiteRequestStore
from pycode.adapters.storage.session_repository import JsonlSessionRepository
from pycode.adapters.storage.summaries import FileSummaryStore
from pycode.adapters.storage.workspace import FileWorkspaceInspector
from pycode.adapters.team.worktrees import WorktreeManager
from pycode.adapters.timing import SystemTiming
from pycode.adapters.tools.artifacts_backend import ToolArtifactsBackend
from pycode.adapters.tools.ask_user import AskUserQuestion
from pycode.adapters.tools.backend import PycodeBackend
from pycode.adapters.tools.bash import Bash, find_bash
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.adapters.tools.plans import ExitPlanMode
from pycode.adapters.tools.read_tool_result import ReadToolResult
from pycode.adapters.tools.schema import JsonSchemaValidator
from pycode.adapters.tools.skills import InstallSkill, SkillSearch
from pycode.adapters.ui.console import ConsoleOutput
from pycode.adapters.ui.tui import PyApp
from pycode.app.background_models import ConfiguredSummaryGenerator
from pycode.app.config import AppConfig, credential_environment
from pycode.app.runtime_launcher import ConfiguredRuntimeLauncher
from pycode.app.team_runtime import ConfiguredWorkers, SynchronousDelegate
from pycode.core.context_budget import InputBudget
from pycode.core.errors import ModelError, StorageError
from pycode.core.execution_recovery import pending_run_entry
from pycode.core.hashing import data_hash
from pycode.core.mcp import choose_tools
from pycode.core.messages import Purpose
from pycode.core.models import ProviderConfig, model_selection
from pycode.core.requests import RetryPolicy
from pycode.core.revert_state import pending_revert
from pycode.core.session_tree import SessionTree
from pycode.core.skill_selection import SkillSelection
from pycode.core.skills import skill_tool_specs
from pycode.core.summaries import SummarySettings
from pycode.core.tool_eviction import EvictionBudget
from pycode.core.tools import ToolSpec
from pycode.core.values import plain_data
from pycode.ports.events import EventConsumer, EventJournal
from pycode.ports.history import MessageJournal, SessionStore
from pycode.ports.tools import Tool
from pycode.services.agent_session import AgentSession
from pycode.services.application import ApplicationService
from pycode.services.context_projector import ContextProjector
from pycode.services.durable_inputs import DurableInputs
from pycode.services.event_journal import MemoryEventJournal
from pycode.services.events import EventStream
from pycode.services.hooks import HookEngine
from pycode.services.input_queue import PendingInputs
from pycode.services.mcp import MCPService
from pycode.services.mcp_bindings import MCPRuntimeBindings
from pycode.services.model_catalog import ModelCatalog
from pycode.services.model_control import ModelControl
from pycode.services.permission import IsolatedWorkerToolGate, ReadOnlyToolGate
from pycode.services.plans import PlanService, PlanToolGate
from pycode.services.prompts import PromptService
from pycode.services.restart import recover_conversation
from pycode.services.revert import RevertService
from pycode.services.session_view import SessionView
from pycode.services.sessions import SessionService
from pycode.services.skill_resources import SkillLoadRecorder, SkillResources
from pycode.services.skills import SkillService, SkillToolGate
from pycode.services.storage_gc import StorageGC
from pycode.services.summary import SummaryService
from pycode.services.tool_eviction import ToolEvictionService
from pycode.services.tool_executor import ToolExecutor
from pycode.services.tool_registry import ToolRegistry
from pycode.services.usage import UsageService
from pycode.workflows.agent import AgentRuntime, RunContext
from pycode.workflows.metered_model import MeteredChatModel
from pycode.workflows.model_fallback import ModelCandidate, ModelFallback
from pycode.workflows.model_requests import ModelRequestRunner
from pycode.workflows.permissions import InterruptToolGate
from pycode.workflows.plans import InterruptPlanPrompt
from pycode.workflows.questions import InterruptQuestionPrompt
from pycode.workflows.summary import prepare_summary
from pycode.workflows.tool_errors import execute_tool_errors
from pycode.workflows.tool_selection import ToolSelector


@dataclass
class Container:
    application: ApplicationService


def build_container(config: AppConfig) -> Container:
    workspace = FileWorkspaceInspector(config.workspace)
    application = ApplicationService(workspace=workspace, version=__version__)
    return Container(application=application)


def build_model_catalog(config: AppConfig, providers: tuple[ProviderConfig, ...]) -> ModelCatalog:
    timing = SystemTiming()
    directory = SDKModelDirectory(
        credential_environment(config, providers), ProviderFailureClassifier(timing)
    )
    store = FileCatalogStore(config.user_data / "model-catalog.json")
    return ModelCatalog(providers, directory, store, timing)


def build_session_repository(config: AppConfig) -> JsonlSessionRepository:
    layout = StorageLayout(config.workspace, config.user_data)
    return JsonlSessionRepository(layout.sessions, str(config.workspace), SystemTiming())


def build_model_control(catalog: ModelCatalog, store=None) -> ModelControl:
    return ModelControl(catalog, ContextProjector(), store)


def default_tools(config, outputs, shell, question_prompt=None, plans=None):
    environment = None
    if config.bash_path_prefix:
        environment = dict(os.environ)
        environment["PATH"] = os.pathsep.join(
            (*config.bash_path_prefix, environment.get("PATH", ""))
        )
    tools = [
        ReadToolResult(outputs),
        Bash(shell, outputs, build_sandbox(), config.sandbox, environment=environment),
    ]
    if question_prompt is not None:
        tools.append(AskUserQuestion(question_prompt))
    if plans is not None:
        tools.append(ExitPlanMode(plans))
    return tuple(tools)


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


def skill_catalog(config):
    return FileSkillCatalog(tuple(skill_roots(config)))


def build_skills(config, store):
    return SkillService(
        store,
        skill_catalog(config),
        index_budget=config.skill_index_budget,
        installer=GitHubSkillInstaller(config.user_data / "skills"),
    )


def skill_tools(config, store):
    skills = build_skills(config, store)
    return (SkillSearch(skills), InstallSkill(skills))


def build_mcp_bindings(store, connections, provider, *, pending=False):
    service = MCPService(store, connections)
    controls = tuple(
        tool(service)
        for tool in (ToolSearch, MCPCall, MCPResources, MCPReadResource, MCPPrompts, MCPGetPrompt)
    )

    def factory(declaration, visible):
        return MCPTool(declaration, connections, visible=visible)

    budget = InputBudget.create(provider.context_window, provider.max_output_tokens)
    return MCPRuntimeBindings(
        service,
        factory,
        controls,
        budget.input_limit,
        pending=pending,
        framework_tools=connections.framework_tool,
        tool_boundary=connections.tool_call,
    )


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
) -> RuntimeContainer:
    checkpointer = checkpointer if checkpointer is not None else InMemorySaver()
    observation = ProviderObservation(provider.implementation)
    factory = ModelFactory(credential_environment(config, (provider,)), observation)
    model = factory.create(provider)
    validator = JsonSchemaValidator()
    from pycode.workflows.memory_tools import memory_tools

    scoped_memory_tools = memory_tools(memory, read_only=config.read_only)
    tools = (*tools, *scoped_memory_tools)
    layout = StorageLayout(config.workspace, config.user_data)
    outputs = FileToolOutputStore(layout.sessions, session_id, history_store)
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
        from pycode.services.permission import MemoryToolGate

        gate = MemoryToolGate(gate, tuple(tool.spec for tool in scoped_memory_tools))
    if plans is not None:
        if config.isolated_worker:
            gate = IsolatedWorkerToolGate(gate)
        gate = PlanToolGate(plans, gate)
    if config.tool_gate is not None:
        gate = config.tool_gate
    if history_store is not None:
        gate = SkillToolGate(history_store, gate)
    snapshots = GitSnapshotStore(
        config.workspace, layout.project / "snapshots", excluded_roots=(config.user_data,)
    )
    hooks = None
    if configured_hooks and history_store is not None:
        hooks = HookEngine(
            configured_hooks,
            history_store,
            ConfiguredHookActions(
                outputs,
                find_bash(),
                build_sandbox(),
                config.sandbox,
                credential_environment(config, (provider,)),
                delegate=build_synchronous_delegate(
                    config, provider, history_store, consumers, run_id
                )
                if config.allow_delegation
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
        hooks=hooks,
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
    )
    if provider.supports_tools:

        def selection_model(current, boundary, validate_response):
            selected = provider
            reference = provider.tool_selector.model
            if reference is not None:
                if model_resolver is None:
                    raise ModelError("selector_model_unavailable", "No configured model resolver")
                selected = model_resolver(reference.provider, reference.model_id)
            if not selected.supports_tools:
                raise ModelError(
                    "selector_model_incompatible", "Selection model must support schema tools"
                )
            selected = replace(
                selected,
                thinking={},
                reasoning_effort=None,
                max_output_tokens=min(
                    selected.max_output_tokens, provider.tool_selector.output_limit
                ),
            )
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
        )
    runtime = AgentRuntime(
        model,
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
        subagents=runtime_subagents(config, provider, history_store, consumers, run_id),
        read_only=config.read_only,
    )
    return RuntimeContainer(
        runtime,
        context,
        request_store,
        factory,
        event_store=event_store,
    )


def runtime_subagents(config, provider, history, consumers, run_id=None):
    if not config.allow_delegation or history is None:
        return ()
    delegate = build_synchronous_delegate(config, provider, history, consumers, run_id)
    return (
        {
            "name": "readonly",
            "description": "Inspect code and report findings without modifications",
            "runnable": RunnableLambda(delegate.invoke),
        },
    )


def build_synchronous_delegate(config, provider, history, consumers, run_id=None):
    layout = StorageLayout(config.workspace, config.user_data)
    workers = ConfiguredWorkers(
        config,
        (provider,),
        open_agent_session,
        None,
        layout.runtime_database,
        consumers,
        session_forker=build_session_repository(config).fork,
    )

    return SynchronousDelegate(workers, history, run_id)


def fork_skill_specs(config, provider, history, consumers, run_id, entries, catalog):
    specs = []
    for entry in entries:
        if entry.mode != "fork":
            continue
        delegate = build_synchronous_delegate(config, provider, history, consumers, run_id)
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


def build_worktree_manager(config):
    layout = StorageLayout(config.workspace, config.user_data)
    return WorktreeManager(config.workspace, layout.project / "worktrees")




def build_lifecycle_hook_engine(launcher, store, snapshot, lifetime):
    layout = StorageLayout(launcher.config.workspace, launcher.config.user_data)
    events = EventStream(
        snapshot.header.session_id, lifetime, launcher.consumers, journal=launcher.event_journal
    )
    outputs = FileToolOutputStore(layout.sessions, snapshot.header.session_id, store)
    engine = HookEngine(
        launcher.configured_hooks,
        store,
        ConfiguredHookActions(
            outputs,
            find_bash(),
            build_sandbox(),
            launcher.config.sandbox,
            dict(os.environ),
            delegate=build_synchronous_delegate(
                launcher.config, launcher.lifecycle_provider, store, launcher.consumers, lifetime
            )
            if launcher.config.allow_delegation
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
    if launcher.config.allow_delegation:
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
            ),
        )
    container.context.skill_resources = SkillResources(
        catalog,
        PythonSkillToolFactory(
            catalog, container.context.outputs, build_sandbox(), launcher.config.sandbox
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


async def open_agent_session(
    config: AppConfig,
    providers: tuple[ProviderConfig, ...],
    session_id: str,
    *,
    create=False,
    tools=(),
    consumers=(),
    refresh_models=True,
    startup_hooks=True,
    connect_mcp=True,
) -> AgentSession:
    if not providers:
        raise ValueError("At least one configured provider is required")
    if not create:
        session_id = await build_session_service(config).resolve(session_id)
    catalog = build_model_catalog(config, providers)
    await catalog.initialize()
    if refresh_models:
        await catalog.refresh()
    store = await build_session_repository(config).open(session_id, create=create)
    try:
        await store.claim_writer()
        layout = StorageLayout(config.workspace, config.user_data)
        receipt_reader = build_memory_service(config, store)
        await recover_conversation(
            store,
            FileToolOutputStore(layout.sessions, session_id, store),
            recover_tool=receipt_reader.recover_active_operation,
        )
        if config.agent_id == "lead" and (layout.project / "worktrees").exists():
            await WorktreeManager(config.workspace, layout.project / "worktrees").scan()
    except BaseException:
        await store.aclose()
        raise
    plan_files = FilePlanStore(config.workspace / ".pycode" / "plans" / session_id)
    plans = PlanService(store, plan_files, InterruptPlanPrompt())
    try:
        await plans.initialize()
    except BaseException:
        await store.aclose()
        raise
    skills = build_skills(config, store)
    try:
        snapshot = await store.snapshot()
        if pending_run_entry(snapshot) is None and pending_revert(snapshot) is None:
            await skills.index()
    except BaseException:
        await store.aclose()
        raise
    models = build_model_control(catalog, store)
    event_journal = MemoryEventJournal()
    hook_roots = [("user", config.user_data / "hooks.json")]
    if config.project_trusted:
        hook_roots.append(("project", config.workspace / ".pycode" / "hooks.json"))
    try:
        owned_hooks = asyncio.create_task(asyncio.to_thread(load_hooks, tuple(hook_roots)))
        try:
            configured_hooks = await asyncio.shield(owned_hooks)
            if not config.allow_delegation:
                configured_hooks = tuple(
                    hook for hook in configured_hooks if hook.action.type != "agent"
                )
        except asyncio.CancelledError:
            await owned_hooks
            raise
    except BaseException:
        await event_journal.aclose()
        await store.aclose()
        raise
    mcp_roots = [config.user_data / "mcp.json"]
    if config.project_trusted:
        mcp_roots.append(config.workspace / ".pycode" / "mcp.json")
    try:
        owned_servers = asyncio.create_task(
            asyncio.to_thread(load_servers, tuple(mcp_roots), config.workspace)
        )
        try:
            servers = await asyncio.shield(owned_servers)
        except asyncio.CancelledError:
            await owned_servers
            raise
    except BaseException:
        await event_journal.aclose()
        await store.aclose()
        raise
    mcp_hash = data_hash([plain_data(server) for server in servers]) if servers else ""
    hook_hash = (
        data_hash([plain_data(hook) for hook in configured_hooks]) if configured_hooks else ""
    )
    current = await store.snapshot()
    pending = pending_run_entry(current)
    if pending is not None and pending.data.get("hook_config_hash", "") != hook_hash:
        await event_journal.aclose()
        await store.aclose()
        raise StorageError(
            "execution_configuration_changed", "Hook configuration changed during execution"
        )
    if pending is not None and pending.data.get("mcp_config_hash", "") != mcp_hash:
        await event_journal.aclose()
        await store.aclose()
        raise StorageError(
            "execution_configuration_changed", "MCP configuration changed during execution"
        )
    connections = None
    try:
        connections = (
            MCPAdapterConnections(servers, credential_environment(config, providers))
            if connect_mcp and any(server.enabled for server in servers)
            else None
        )
        if connections is not None:
            connections.start()
        durable_inputs = DurableInputs(store)
        memory = build_memory_service(config, store, providers[0], consumers)
        launcher = ConfiguredRuntimeLauncher(
            config,
            tools,
            consumers,
            event_journal,
            configured_hooks,
            mcp_connections=connections,
            mcp_config_hash=mcp_hash,
            plans=plans,
            plan_backend=plan_files.backend,
            durable_inputs=durable_inputs,
            runtime_factory=build_configured_run,
            model_resolver=catalog.resolve,
            hook_factory=build_lifecycle_hook_engine,
            lifecycle_provider=providers[0],
            memory=memory,
            prompt_factory=lambda history: PromptService(
                history,
                ProjectPromptSource(
                    config.workspace, Path(__file__).parents[1] / "resources" / "system-prompt.txt"
                ),
            ),
        )
        snapshots = GitSnapshotStore(
            config.workspace,
            StorageLayout(config.workspace, config.user_data).project / "snapshots",
            excluded_roots=(config.user_data,),
        )
        session = AgentSession(
            store,
            ContextProjector(),
            models,
            launcher,
            providers[0],
            durable_inputs,
            RevertService(store, snapshots),
            build_session_view(
                config,
                store,
                session_id=session_id,
                consumers=consumers,
                event_journal=event_journal,
                tools=tools,
            ),
            skills=skills,
            hook_config_hash=hook_hash,
            mcp_config_hash=mcp_hash,
            plans=plans,
            storage=build_storage_gc(config, owned_session_id=session_id),
            memory=memory,
            recover_tool=memory.recover_active_operation,
            recovery_outputs=FileToolOutputStore(layout.sessions, session_id, store),
        )
    except BaseException:
        if connections is not None:
            await connections.aclose()
        await event_journal.aclose()
        await store.aclose()
        raise
    try:
        await session.initialize_memory()
        if startup_hooks:
            await launcher.startup(store)
    except BaseException:
        launcher.abort_startup()
        await session.aclose()
        raise
    return session


def build_session_view(
    config, store, *, session_id, consumers=(), owns_history=False, event_journal=None, tools=()
):
    database = (
        config.request_database
        or StorageLayout(config.workspace, config.user_data).runtime_database
    )
    snapshot_tools = (
        *tools,
        *skill_tools(config, store),
        *default_tools(
            config,
            FileToolOutputStore(
                StorageLayout(config.workspace, config.user_data).sessions, "view", store
            ),
            "",
            InterruptQuestionPrompt(),
        ),
    )
    specs = (*ToolRegistry(JsonSchemaValidator(), snapshot_tools).list_specs(), ExitPlanMode.spec)

    def settings(projection):
        selection = projection.selection
        if (
            selection is None
            or selection.capabilities.context_window is None
            or selection.capabilities.max_output_tokens is None
        ):
            return None
        effective = []
        for spec in projection.checkpoint.get("model_tools", ()):
            effective.append(ToolSpec(**plain_data(spec)))
        if not effective:
            effective.extend(specs)
            effective.extend(skill_tool_specs(projection.checkpoint.get("skills", ())))
            effective.extend(mcp_view_specs(projection, selection))
        effective.sort(key=lambda spec: spec.name)
        return SummarySettings(
            selection,
            tuple(effective) if selection.capabilities.supports_tools else (),
            InputBudget.create(
                selection.capabilities.context_window, selection.capabilities.max_output_tokens
            ),
            config.summary_keep_recent_tokens,
        )

    return SessionView(
        store,
        ContextProjector(),
        UsageService(store, SQLiteRequestStore(database)),
        event_journal or MemoryEventJournal(),
        consumers,
        owns_history=owns_history,
        context_settings=settings,
        outputs=FileToolOutputStore(
            StorageLayout(config.workspace, config.user_data).sessions, session_id, store
        ),
    )


def mcp_view_specs(projection, selection):
    catalog = projection.checkpoint.get("mcp")
    if catalog is None:
        return ()
    controls = tuple(
        tool.spec
        for tool in (ToolSearch, MCPCall, MCPResources, MCPReadResource, MCPPrompts, MCPGetPrompt)
    )
    budget = InputBudget.create(
        selection.capabilities.context_window, selection.capabilities.max_output_tokens
    )
    declared = choose_tools(catalog, budget.input_limit)
    return (*controls, *(spec for spec in declared if spec.model_visible))


async def open_session_view(config, session_id):
    store = await build_session_repository(config).open(session_id)
    return build_session_view(config, store, session_id=session_id, owns_history=True)


def build_session_service(config):
    return SessionService(build_session_repository(config))


def build_console_output(output_format):
    return ConsoleOutput(output_format, sys.stdout)


class ConfiguredInteractiveSessions:
    def __init__(self, config, providers, tools=()):
        self._config = config
        self._providers = providers
        self._tools = tools
        self._sessions = build_session_service(config)

    async def open(self, session_id, *, create, consumer):
        if not create:
            session_id = await self._sessions.resolve(session_id)
        return await open_agent_session(
            self._config,
            self._providers,
            session_id,
            create=create,
            consumers=(consumer,),
            tools=self._tools,
            refresh_models=False,
        )

    async def list_sessions(self):
        return await self._sessions.list_sessions()

    async def sandbox_status(self):
        return await build_sandbox().status(self._config.sandbox)

    async def fork(self, source_id, leaf_id, target_id):
        return await self._sessions.fork(source_id, leaf_id, target_id)


def build_tui(config, providers, *, session_id=None, tools=()):
    return PyApp(ConfiguredInteractiveSessions(config, providers, tools), session_id=session_id)


def build_sandbox():
    return SeatbeltSandbox() if sys.platform == "darwin" else UnavailableSandbox()


async def sandbox_status(config):
    return await build_sandbox().status(config.sandbox)


def skill_roots(config):
    roots = [
        ("builtin", Path(__file__).parents[1] / "resources" / "skills"),
        ("user", config.user_data / "skills"),
    ]
    if config.project_trusted:
        roots.extend(
            [
                ("project-agents", config.workspace / ".agents" / "skills"),
                ("project", config.workspace / ".pycode" / "skills"),
            ]
        )
    return roots


def skill_sources(config):
    return ["/skills/" + source + "/" for source, root in skill_roots(config) if root.is_dir()]


def build_backend(
    config,
    *,
    plan_backend=None,
    workspace_files=None,
    outputs=None,
    history=None,
    skill_selection=None,
):
    routes = {
        "/workspace/": workspace_files
        or PycodeBackend(
            config.workspace,
            read_only=config.read_only,
            excluded_roots=(config.user_data,),
        )
    }
    if plan_backend is not None:
        routes["/plans/"] = plan_backend
    if outputs is not None and history is not None:
        routes["/large_tool_results/"] = ToolArtifactsBackend(outputs, history)
        routes["/conversation_history/"] = ConversationHistoryBackend(history)
    for source, root in skill_roots(config):
        if root.is_dir():
            prefix = "/skills/" + source + "/"
            routes[prefix] = SkillBackend(root, selection=skill_selection, prefix=prefix)
    return CompositeBackend(default=StateBackend(), routes=routes)


def build_storage_gc(config, *, owned_session_id=None):
    return StorageGC(
        FileStorageObjects(config.user_data, config.workspace, owned_session_id=owned_session_id),
        build_session_repository(config),
        SystemTiming(),
    )


@dataclass
class MeteredModelOwner:
    model: object
    requests: object
    factory: object
    records: object
    events: object

    async def aclose(self):
        try:
            await self.factory.aclose()
        finally:
            try:
                await self.records.aclose()
            finally:
                await self.events.aclose()


def build_background_model(
    config,
    provider,
    consumers,
    session_id,
    run_id,
    purpose,
    *,
    retry_policy=None,
    candidate_index=0,
):
    timing = SystemTiming()
    observation = ProviderObservation(provider.implementation)
    factory = ModelFactory(credential_environment(config, (provider,)), observation)
    model = factory.create(provider)
    records = SQLiteRequestStore(StorageLayout(config.workspace, config.user_data).runtime_database)
    journal = MemoryEventJournal()
    events = EventStream(session_id, run_id, consumers, journal=journal)
    requests = ModelRequestRunner(
        records,
        ProviderFailureClassifier(timing),
        timing,
        retry_policy or RetryPolicy(),
        events,
        factory.selection(provider),
        session_id,
        run_id,
        purpose=purpose,
        observation=observation,
        candidate_index=candidate_index,
    )
    return MeteredModelOwner(model, requests, factory, records, journal)


def build_summary_generator(config, provider, consumers):
    def model_factory(selected, session_id, run_id, purpose):
        return build_background_model(config, selected, consumers, session_id, run_id, purpose)

    def records_factory():
        return SQLiteRequestStore(
            StorageLayout(config.workspace, config.user_data).runtime_database
        )

    return ConfiguredSummaryGenerator(provider, model_factory, records_factory)


def build_memory_service(config, history, provider=None, consumers=()):
    from pycode.adapters.storage.json_memory import JsonMemoryStore
    from pycode.adapters.storage.memory_jobs import FileMemoryJobs
    from pycode.adapters.storage.memory_moves import FileMemoryMoves
    from pycode.core.memory_evidence import MemoryWritePermit
    from pycode.services.json_memory import JsonMemoryService
    from pycode.services.memory_sources import MemorySourcePlanner

    user_id = hashlib.sha256(str(config.user_data.resolve()).encode()).hexdigest()[:24]
    project_id = StorageLayout(config.workspace, config.user_data).project.name
    user_namespace = ("memories", "user", user_id)
    project_namespace = ("memories", "project", project_id)
    roots = {
        user_namespace: config.user_data / "memory",
        project_namespace: config.workspace / ".pycode" / "memory",
    }
    scopes = {user_namespace: "user", project_namespace: "project"}

    async def model_selection_required(query, directory):
        raise ModelError("memory_model_required", "Memory directory selection needs a provider")

    store = JsonMemoryStore(
        roots,
        selector=model_selection_required,
        validate_change=MemoryWritePermit(),
    )
    layout = StorageLayout(config.workspace, config.user_data)

    # Session IDs are read only after the repository has established their trusted identity.
    class SessionOutputReader:
        def __init__(self):
            self.outputs = None

        async def read(
            self, output_id, offset, limit, max_bytes, byte_offset=0, *, absolute_byte_offset=None
        ):
            if self.outputs is None:
                snapshot = await history.snapshot()
                self.outputs = FileToolOutputStore(
                    layout.sessions, snapshot.header.session_id, history
                )
            return await self.outputs.read(
                output_id,
                offset,
                limit,
                max_bytes,
                byte_offset,
                absolute_byte_offset=absolute_byte_offset,
            )

    return JsonMemoryService(
        history,
        store,
        scopes,
        SystemTiming(),
        FileMemoryJobs(roots),
        MemorySourcePlanner(output_reader=SessionOutputReader()),
        updater=build_memory_updater(config, provider, consumers) if provider is not None else None,
        mode=config.memory_mode,
        moves=FileMemoryMoves(store, scopes, user_namespace),
    )


def build_memory_update_store(roots, model, permit):
    from pycode.adapters.storage.json_memory import JsonMemoryStore
    from pycode.workflows.memory_query import MemoryDirectorySelector

    return JsonMemoryStore(
        roots,
        selector=MemoryDirectorySelector(model),
        validate_change=permit,
        directory_tokens=min(12000, max(128, model.input_limit - 1000)),
        max_queries=8,
    )


def build_memory_updater(config, provider, consumers=()):
    from pycode.app.background_models import ConfiguredMemoryUpdater

    def model_factory(selected, session_id, run_id, purpose):
        return build_background_model(config, selected, consumers, session_id, run_id, purpose)

    return ConfiguredMemoryUpdater(provider, model_factory, build_memory_update_store)
