"""Composition of session capabilities; concrete adapters stay at this boundary."""

import asyncio
from dataclasses import dataclass
from pathlib import Path

from pycode import __version__
from pycode.app.composition.capabilities import (
    build_output_store,
    build_skills,
    default_tools,
    skill_tools,
)
from pycode.app.composition.memory import build_memory_service
from pycode.app.composition.models import build_model_catalog, build_model_control
from pycode.app.composition.runtime import build_configured_run, build_lifecycle_hook_engine
from pycode.app.composition.team import build_team, build_worktree_manager
from pycode.app.config import AppConfig, credential_environment
from pycode.app.runtime_launcher import ConfiguredRuntimeLauncher
from pycode.context import ContextProjector, InputBudget, PromptService, SummarySettings
from pycode.context.adapters.storage.prompts import (
    ProjectPromptSource,
)
from pycode.hooks import validate_hooks_recovery
from pycode.hooks.adapters.config import load_hooks
from pycode.kernel import MemoryEventJournal, SystemTiming, ToolSpec, data_hash, plain_data
from pycode.mcp import choose_tools, validate_mcp_recovery
from pycode.mcp.adapters.config import load_servers
from pycode.mcp.adapters.manager import MCPAdapterConnections
from pycode.mcp.adapters.tools import (
    MCPCall,
    MCPGetPrompt,
    MCPPrompts,
    MCPReadResource,
    MCPResources,
    ToolSearch,
)
from pycode.models import ProviderConfig, UsageService
from pycode.models.adapters.storage.requests import SQLiteRequestStore
from pycode.plans import PlanService
from pycode.plans.adapters.plans import InterruptPlanPrompt
from pycode.plans.adapters.storage.plans import FilePlanStore
from pycode.plans.adapters.tools.plans import ExitPlanMode
from pycode.session import (
    AgentSession,
    ApplicationService,
    DurableInputs,
    RevertService,
    SessionRunOwnership,
    SessionService,
    SessionView,
    StorageGC,
    pending_revert,
    pending_run_entry,
    recover_conversation,
)
from pycode.session.adapters.storage.layout import StorageLayout
from pycode.session.adapters.storage.objects import FileStorageObjects
from pycode.session.adapters.storage.session_repository import JsonlSessionRepository
from pycode.session.adapters.storage.workspace import FileWorkspaceInspector
from pycode.skills import skill_tool_specs
from pycode.snapshot.adapters.git_tree import GitSnapshotStore
from pycode.tools import ToolRegistry
from pycode.tools.adapters.backend import PycodeBackend
from pycode.tools.adapters.questions import InterruptQuestionPrompt
from pycode.tools.adapters.schema import JsonSchemaValidator


@dataclass
class Container:
    application: ApplicationService


def build_container(config: AppConfig) -> Container:
    workspace = FileWorkspaceInspector(config.workspace)
    application = ApplicationService(workspace=workspace, version=__version__)
    return Container(application=application)


def build_session_repository(config: AppConfig) -> JsonlSessionRepository:
    layout = StorageLayout(config.workspace, config.user_data)
    return JsonlSessionRepository(layout.sessions, str(config.workspace), SystemTiming())


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
    team_member=None,
    prompt_suffix="",
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
        await recover_conversation(store, build_output_store(layout.sessions, session_id, store))
        if config.agent_id == "lead" and (layout.project / "worktrees").exists():
            await build_worktree_manager(config).scan()
    except BaseException:
        await store.aclose()
        raise
    plan_files = FilePlanStore(
        config.workspace / ".pycode" / "plans" / session_id, backend_factory=PycodeBackend
    )
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
            if config.agent_id != "lead":
                configured_hooks = tuple(
                    hook for hook in configured_hooks if hook.action.type == "prompt"
                )
            elif not config.allow_delegation:
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
    if config.agent_id != "lead":
        servers = ()
    mcp_hash = data_hash([plain_data(server) for server in servers]) if servers else ""
    hook_hash = (
        data_hash([plain_data(hook) for hook in configured_hooks]) if configured_hooks else ""
    )
    current = await store.snapshot()
    pending = pending_run_entry(current)
    try:
        validate_hooks_recovery(pending, hook_hash)
        validate_mcp_recovery(pending, mcp_hash)
    except BaseException:
        await event_journal.aclose()
        await store.aclose()
        raise
    connections = None
    try:
        connections = (
            MCPAdapterConnections(
                servers, credential_environment(config, providers), validator=JsonSchemaValidator()
            )
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
            team_member=team_member,
            prompt_factory=lambda history: PromptService(
                history,
                ProjectPromptSource(
                    config.workspace,
                    Path(__file__).parents[2] / "resources" / "system-prompt.txt",
                    suffix=prompt_suffix,
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
            recovery_outputs=build_output_store(layout.sessions, session_id, store),
        )
    except BaseException:
        if connections is not None:
            await connections.aclose()
        await event_journal.aclose()
        await store.aclose()
        raise
    try:
        if config.allow_delegation and config.agent_id == "lead" and team_member is None:
            team = await build_team(
                config,
                providers,
                catalog,
                store,
                consumers,
                event_journal,
                plans,
                session_opener=open_agent_session,
                repository_factory=build_session_repository,
            )
            session._team = team
            launcher.team_member = team.member("lead")
            await team.initialize()
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
            build_output_store(
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
        UsageService(store, SQLiteRequestStore(database), ownership=SessionRunOwnership()),
        event_journal or MemoryEventJournal(),
        consumers,
        owns_history=owns_history,
        context_settings=settings,
        outputs=build_output_store(
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


def build_storage_gc(config, *, owned_session_id=None):
    return StorageGC(
        FileStorageObjects(config.user_data, config.workspace, owned_session_id=owned_session_id),
        build_session_repository(config),
        SystemTiming(),
    )
