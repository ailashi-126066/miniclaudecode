"""Composition of capabilities capabilities; concrete adapters stay at this boundary."""

import os
import sys
from pathlib import Path

from deepagents.backends import CompositeBackend, StateBackend

from pycode.context import InputBudget
from pycode.hooks.adapters.actions import ConfiguredHookActions
from pycode.mcp import MCPRuntimeBindings, MCPService
from pycode.mcp.adapters.tools import (
    MCPCall,
    MCPGetPrompt,
    MCPPrompts,
    MCPReadResource,
    MCPResources,
    MCPTool,
    ToolSearch,
)
from pycode.plans.adapters.tools.plans import ExitPlanMode
from pycode.sandbox.adapters.seatbelt import RequiredSandbox, SeatbeltSandbox, UnavailableSandbox
from pycode.sandbox.adapters.tools.bash import Bash
from pycode.sandbox.adapters.tools.process_runner import run_process
from pycode.session import SessionCatalogWriteGuard
from pycode.session.adapters.storage.header_reader import FileSessionHeaderReader
from pycode.session.adapters.storage.history_backend import ConversationHistoryBackend
from pycode.skills import SkillService
from pycode.skills.adapters.backend import SkillBackend
from pycode.skills.adapters.catalog import FileSkillCatalog
from pycode.skills.adapters.install import GitHubSkillInstaller
from pycode.skills.adapters.python_tool import PythonSkillToolFactory
from pycode.skills.adapters.tools.skills import InstallSkill, SkillSearch
from pycode.tools.adapters.artifacts_backend import ToolArtifactsBackend
from pycode.tools.adapters.ask_user import AskUserQuestion
from pycode.tools.adapters.backend import PycodeBackend
from pycode.tools.adapters.output_store import FileToolOutputStore
from pycode.tools.adapters.read_tool_result import ReadToolResult
from pycode.tools.adapters.schema import JsonSchemaValidator


def default_tools(config, outputs, shell, question_prompt=None, plans=None):
    environment = None
    if config.bash_path_prefix:
        environment = dict(os.environ)
        environment["PATH"] = os.pathsep.join(
            (*config.bash_path_prefix, environment.get("PATH", ""))
        )
    tools = [
        ReadToolResult(outputs),
        Bash(shell, outputs, worker_sandbox(config), config.sandbox, environment=environment),
    ]
    if question_prompt is not None:
        tools.append(AskUserQuestion(question_prompt))
    if plans is not None:
        tools.append(ExitPlanMode(plans))
    return tuple(tools)


def skill_catalog(config):
    return FileSkillCatalog(tuple(skill_roots(config)), validator=JsonSchemaValidator())


def build_skills(config, store):
    return SkillService(
        store,
        skill_catalog(config),
        index_budget=config.skill_index_budget,
        catalog_write_guard=SessionCatalogWriteGuard(),
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


def worker_sandbox(config):
    backend = build_sandbox()
    return RequiredSandbox(backend) if config.isolated_worker else backend


def build_sandbox():
    return SeatbeltSandbox() if sys.platform == "darwin" else UnavailableSandbox()


def skill_roots(config):
    roots = [
        ("builtin", Path(__file__).parents[2] / "resources" / "skills"),
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
            routes[prefix] = SkillBackend(
                PycodeBackend(root, read_only=True), selection=skill_selection, prefix=prefix
            )
    return CompositeBackend(default=StateBackend(), routes=routes)


def build_output_store(sessions, session_id, journal=None, **kwargs):
    kwargs.setdefault("header_reader", FileSessionHeaderReader(sessions))
    return FileToolOutputStore(sessions, session_id, journal, **kwargs)


def skill_read_grant(sandbox, directory):
    return sandbox.for_skill(directory) if isinstance(sandbox, RequiredSandbox) else sandbox


def build_python_skill_factory(*args, **kwargs):
    return PythonSkillToolFactory(
        *args, process_runner=run_process, skill_read_grant=skill_read_grant, **kwargs
    )


def build_hook_actions(*args, **kwargs):
    return ConfiguredHookActions(*args, process_runner=run_process, **kwargs)
