"""Connect feature-owned products for one run; do not construct their adapters here."""

from functools import partial

from pycode.agent import RunContext
from pycode.app.composition.bindings import build_runtime_bindings
from pycode.app.composition.context import build_summary_generator
from pycode.app.composition.hooks import build_hook_engine
from pycode.app.composition.modules import default_modules
from pycode.app.composition.outputs import build_outputs
from pycode.app.composition.skills import skill_settings
from pycode.app.configuration.credentials import credential_environment
from pycode.app.configuration.settings import AppConfig
from pycode.app.lifecycle.run import RunAssembly, RuntimeContainer
from pycode.context import EvictionBudget, InputBudget, SummarySettings
from pycode.hooks import ToolHookBridge
from pycode.kernel import EventStream, Purpose
from pycode.kernel.adapters.event_journal import MemoryEventJournal
from pycode.models import ProviderConfig, model_selection, selector_provider
from pycode.session import (
    DurableInputs,
    PendingInputs,
    SessionCatalogWriteGuard,
    assign_initial_title,
)


async def build_runtime(
    config: AppConfig,
    provider: ProviderConfig,
    *,
    session_id,
    run_id,
    journal,
    tools=(),
    consumers=(),
    checkpointer=None,
    retry_policy=None,
    agent_id="lead",
    purpose: Purpose = "agent",
    history_store=None,
    eviction_budget=None,
    summary_service=None,
    event_journal=None,
    configured_hooks=(),
    owned_hook_tasks=None,
    mcp_config_hash="",
    plans=None,
    plan_backend=None,
    durable_inputs=None,
    effective_tools=(),
    model_resolver=None,
    memory=None,
    team_member=None,
    skills_run=None,
    mcp_run=None,
    outputs=None,
    modules=None,
) -> RuntimeContainer:
    async with RunAssembly() as assembling:
        modules = default_modules(config) if modules is None else modules
        checkpointer = checkpointer if checkpointer is not None else modules.agent.checkpoint()
        outputs = outputs or build_outputs(modules, session_id, history_store)
        if skills_run is None and modules.skills is not None and history_store is not None:
            skills_session = modules.skills.open_session(
                skill_settings(config),
                history_store,
                catalog_write_guard=SessionCatalogWriteGuard(),
            )
            skills_run = await skills_session.prepare_run(
                outputs=outputs,
                sandbox=modules.sandbox.worker(),
                sandbox_settings=config.sandbox,
                passthrough=config.subprocess_passthrough,
                read_only=config.read_only,
                reserved_tools=(
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
            )
        memory_tools = modules.memory.tools(memory, read_only=config.read_only)
        team_tools = modules.team.tools(team_member, provider) if team_member is not None else ()
        supplied = (*tools, *memory_tools, *team_tools)
        enabled = (
            (
                *supplied,
                *(skills_run.tools if skills_run else ()),
                *(mcp_run.tools if mcp_run else ()),
                *modules.tools.default_tools(
                    outputs, modules.tools.question_prompt() if config.agent_id == "lead" else None
                ),
                modules.sandbox.bash(outputs),
                *((modules.plans.exit_tool(plans),) if plans is not None else ()),
            )
            if provider.supports_tools
            else ()
        )
        if config.read_only:
            enabled = tuple(tool for tool in enabled if tool.spec.category == "read")
        registry = modules.tools.registry(enabled)
        if skills_run is not None:
            skills_run.reserve_tools(tuple(spec.name for spec in registry.list_specs()))
        event_store = MemoryEventJournal() if event_journal is None else None
        assembling.events = event_store.aclose if event_store else None
        events = EventStream(session_id, run_id, consumers, journal=event_journal or event_store)
        snapshots = modules.snapshots.store()
        gate = modules.tools.gate(
            modules.sandbox.backend(),
            config.sandbox,
            interruptible=checkpointer is not None,
            read_only=config.read_only,
            custom=config.tool_gate,
            memory_specs=tuple(tool.spec for tool in memory_tools),
            plans=plans,
            scope=skills_run.permission_constraint if skills_run else None,
            worker=config.agent_id != "lead",
            worker_sandbox=modules.sandbox.worker(),
            isolated_worker=config.isolated_worker,
            team=team_member.team if team_member else None,
        )
        hooks = None
        if configured_hooks and history_store is not None:
            actions = modules.hooks.actions(
                outputs,
                modules.sandbox.shell(),
                modules.sandbox.backend(),
                config.sandbox,
                credential_environment(config, (provider,)),
                process_runner=modules.sandbox.process_runner,
                passthrough=config.subprocess_passthrough,
                delegate=modules.team.delegate(team_member.team, provider, history_store, run_id)
                if config.allow_delegation and team_member is not None
                else None,
            )
            hooks = build_hook_engine(
                configured_hooks,
                history_store,
                actions,
                outputs,
                events,
                modules=modules,
                snapshots=snapshots,
                owned_tasks=owned_hook_tasks,
                plans=plans,
                read_only=config.read_only,
            )
        assembling.hooks = hooks.aclose if hooks else None
        executor = modules.tools.executor(
            registry,
            gate,
            events,
            outputs,
            snapshots=snapshots,
            hooks=ToolHookBridge(hooks) if hooks is not None else None,
        )
        budget = InputBudget.create(provider.context_window, provider.max_output_tokens)
        eviction = (
            modules.context.eviction(
                history_store,
                modules.projector(),
                eviction_budget or EvictionBudget(budget.input_limit),
                events,
            )
            if history_store is not None
            else None
        )
        workspace_files = modules.tools.backend(
            config.workspace, read_only=config.read_only, excluded_roots=(config.user_data,)
        )
        owner = await modules.models.owner(
            provider,
            consumers,
            session_id,
            run_id,
            "subagent" if config.agent_id != "lead" and purpose == "agent" else purpose,
            retry_policy=retry_policy,
            agent_id=config.agent_id if agent_id == "lead" else agent_id,
            events=events,
        )
        assembling.model = owner.aclose
        context = RunContext(
            session_id=session_id,
            run_id=run_id,
            workspace=str(config.workspace),
            selection=owner.requests.selection,
            events=events,
            journal=journal,
            executor=executor,
            registry=registry,
            inputs=PendingInputs(),
            requests=owner.requests,
            history_store=history_store,
            projector=modules.projector() if history_store else None,
            outputs=outputs,
            eviction=eviction,
            durable_inputs=durable_inputs
            or (DurableInputs(history_store) if history_store else None),
            agent_id=config.agent_id if agent_id == "lead" else agent_id,
            hooks=hooks,
            mcp_config_hash=mcp_config_hash,
            plans=plans,
            workspace_files=workspace_files,
            model_tools=effective_tools,
            read_only=config.read_only,
            team=team_member,
        )
        if provider.supports_tools:
            context.tool_selector = modules.models.selector(
                provider.tool_selector,
                _selection_owner(modules, provider, model_resolver, consumers),
                required_tools=lambda: (
                    *modules.agent.selector_required_names(),
                    *modules.tools.selector_required_names(),
                    *(tool.spec.name for tool in memory_tools),
                    *(
                        modules.team.selector_required_names(team_member, provider)
                        if team_member
                        else ()
                    ),
                    *(modules.plans.selector_required_names(plans) if plans is not None else ()),
                    *(modules.skills.selector_required_names() if modules.skills else ()),
                ),
            )
        if history_store is not None:
            settings = SummarySettings(
                context.selection,
                effective_tools or registry.list_specs(),
                budget,
                config.summary_keep_recent_tokens,
            )
            generator = build_summary_generator(config, provider, consumers, modules=modules)
            if summary_service is None:
                summary_service = modules.context.summary(
                    history_store,
                    modules.projector(),
                    modules.sessions.layout.project / "summaries",
                    generator,
                    settings,
                    events,
                    on_compacted=memory.compacted if memory is not None else None,
                )
                assembling.summary = summary_service.aclose
            else:
                summary_service.configure(settings, generator, events)
            context.summary = summary_service
        native_bindings = (mcp_run.native_binding,) if mcp_run else ()
        tool_extensions = (skills_run.tool_extension,) if skills_run else ()
        calibrator = partial(
            modules.models.calibrate_model_request,
            resource_lookup=skills_run.tool_extension.get_tool if skills_run else None,
        )
        if provider.fallback_models and config.agent_id == "lead" and purpose == "agent":

            async def fallback_owner(selected, current, index):
                return await modules.models.owner(
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
                    current.summary.configure(
                        SummarySettings(
                            current.selection,
                            current.model_tools or current.registry.model_specs(),
                            InputBudget.create(selected.context_window, selected.max_output_tokens),
                            config.summary_keep_recent_tokens,
                        ),
                        build_summary_generator(config, selected, consumers, modules=modules),
                        current.events,
                    )
                if changed:
                    current.system_hash = ""

            context.model_fallback = modules.models.fallback(
                modules.models.candidate(owner, provider),
                provider.fallback_models,
                model_resolver,
                fallback_owner,
                configure_candidate,
                calibrator=calibrator,
            )
        assembling.fallback = context.model_fallback.aclose if context.model_fallback else None
        routes = {"/workspace/": workspace_files}
        if plan_backend is not None:
            routes["/plans/"] = plan_backend
        if history_store is not None:
            routes["/large_tool_results/"] = modules.tools.artifacts(outputs, history_store)
            routes["/conversation_history/"] = modules.sessions.history_backend(history_store)
        extension = (
            skills_run.extension
            if skills_run
            else (
                modules.skills.standalone_extension(skill_settings(config))
                if modules.skills
                else None
            )
        )
        if skills_run:
            routes.update(skills_run.routes)
        elif modules.skills:
            routes.update(modules.skills.standalone_routes(skill_settings(config)))
        instructions, instruction_backend = modules.context.instructions(
            config.workspace, config.user_data, trusted=config.project_trusted
        )

        async def prepare_tools():
            if mcp_run is not None:
                await mcp_run.refresh(registry)
            if skills_run is not None:
                skills_run.reserve_tools(tuple(spec.name for spec in registry.list_specs()))

        preparations = (prepare_tools,) if mcp_run or skills_run else ()
        bindings = build_runtime_bindings(
            modules=modules,
            framework_extensions=(extension,) if extension else (),
            model_filters=(extension.filter_request,) if extension else (),
            before_model=preparations,
            tool_extensions=tool_extensions,
            native_bindings=native_bindings,
            calibrator=calibrator,
            question_extensions=(mcp_run.question_extension,) if mcp_run else (),
        )
        runtime = modules.agent.runtime(
            owner.model,
            bindings=bindings,
            title_assignment=BoundTitleAssignment(history_store) if history_store else None,
            checkpointer=checkpointer,
            backend=modules.tools.composite(routes),
            subagents=skills_run.subagents if skills_run else (),
            read_only=config.read_only,
            instructions=instructions,
            instruction_backend=instruction_backend,
        )
        return RuntimeContainer(
            runtime,
            context,
            owner.records,
            owner,
            event_store=event_store,
            preparations=preparations,
        )


def _selection_owner(modules, provider, resolver, consumers):
    async def create(current, boundary, validate_response):
        selected = selector_provider(provider, resolver)
        limit = min(
            provider.tool_selector.input_limit,
            InputBudget.create(selected.context_window, selected.max_output_tokens).input_limit,
        )
        owner = await modules.models.owner(
            selected, consumers, current.session_id, current.run_id, "tool_selection"
        )
        owner.model = modules.models.metered(
            owner,
            logical_prefix=boundary + ":tool_selection",
            input_limit=limit,
            response_validator=validate_response,
        )
        return owner

    return create


class BoundTitleAssignment:
    def __init__(self, history):
        self.history = history

    async def assign(self, message):
        await assign_initial_title(self.history, message)
