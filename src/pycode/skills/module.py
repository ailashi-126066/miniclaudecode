"""Skill-owned catalog, routes, resources, framework loading and permission assembly."""

from dataclasses import dataclass
from pathlib import Path

from pycode.kernel import SessionTree, ToolError
from pycode.skills.adapters.backend import SkillBackend
from pycode.skills.adapters.catalog import FileSkillCatalog
from pycode.skills.adapters.extension import SkillsFrameworkExtension, SkillsToolExtension
from pycode.skills.adapters.fork import ForkSkillDelegate
from pycode.skills.adapters.install import GitHubSkillInstaller
from pycode.skills.adapters.python_tool import PythonSkillToolFactory
from pycode.skills.adapters.tools.skills import InstallSkill, SkillSearch
from pycode.skills.domain.skill_selection import SkillSelection
from pycode.skills.domain.skills import skill_tool_specs
from pycode.skills.services.projection import SkillsProjection as SkillsProjection
from pycode.skills.services.scope import SkillsScopeConstraint
from pycode.skills.services.skill_resources import SkillLoadRecorder, SkillResources
from pycode.skills.services.skills import SkillService


@dataclass(frozen=True)
class SkillsSettings:
    workspace: Path
    user_data: Path
    project_trusted: bool = False
    index_budget: int = 2000


@dataclass(frozen=True)
class SkillsRun:
    tools: tuple
    routes: dict
    extension: SkillsFrameworkExtension
    tool_extension: SkillsToolExtension
    permission_constraint: SkillsScopeConstraint
    subagents: tuple = ()

    def reserve_tools(self, names: tuple[str, ...]) -> None:
        resources = self.extension.resources
        additions = frozenset(names)
        if resources.tools.keys() & additions:
            raise ToolError("tool_name_conflict", "Skill tool shadows a built-in tool")
        resources.reserved_tools |= additions


class SkillsSession:
    def __init__(self, module, settings, history, catalog_write_guard):
        self.module, self.settings, self.history = module, settings, history
        self.roots = module._roots(settings)
        self.catalog = FileSkillCatalog(self.roots, validator=module.schema)
        self.service = SkillService(
            history,
            self.catalog,
            catalog_write_guard=catalog_write_guard,
            index_budget=settings.index_budget,
            installer=GitHubSkillInstaller(settings.user_data / "skills"),
        )

    async def initialize(self):
        return await self.service.index()

    async def prepare_run(
        self,
        *,
        outputs,
        sandbox,
        sandbox_settings,
        passthrough=(),
        read_only=False,
        reserved_tools=(),
        fork_delegate=None,
    ) -> SkillsRun:
        snapshot = await self.history.snapshot()
        index = await self.catalog.scan()
        indexed = await self.service.refresh(index)
        paths = tuple(self.catalog.virtual_path(entry) for entry in index.entries)
        selection = SkillSelection(indexed["dispatch"], paths)
        for saved in SessionTree(snapshot.index).path(snapshot.leaf_id):
            if saved.message is not None and saved.message.role == "user":
                selected_path = saved.message.provider_metadata.get("skill_request", {}).get("path")
                if selected_path in paths:
                    selection.select((selected_path,))
        routes = {}
        for source, root in self.roots:
            if root.is_dir():
                prefix = "/skills/" + source + "/"
                routes[prefix] = SkillBackend(
                    self.module.backend_factory(root, read_only=True),
                    selection=selection,
                    prefix=prefix,
                )
        resources = SkillResources(
            self.catalog,
            PythonSkillToolFactory(
                self.catalog,
                outputs,
                sandbox,
                sandbox_settings,
                process_runner=self.module.process_runner,
                skill_read_grant=self.module.skill_read_grant,
                passthrough=passthrough,
            ),
            index.entries,
            reserved_tools=reserved_tools,
            history=self.history,
        )
        constraint = SkillsScopeConstraint(self.history)
        recorder = SkillLoadRecorder(self.history, self.catalog)
        tools = self.module.session_tools(self, read_only=read_only)
        subagents = ()
        if fork_delegate is not None:
            subagents = tuple(
                ForkSkillDelegate(
                    fork_delegate, self.history, entry, self.catalog.virtual_path(entry)
                ).spec()
                for entry in index.entries
                if entry.mode == "fork"
            )
        return SkillsRun(
            tools,
            routes,
            SkillsFrameworkExtension(resources, selection, tuple(routes), constraint),
            SkillsToolExtension(resources, recorder, selection, constraint),
            constraint,
            subagents,
        )


class SkillsModule:
    def __init__(self, *, schema, process_runner, backend_factory, skill_read_grant):
        self.schema, self.process_runner = schema, process_runner
        self.backend_factory, self.skill_read_grant = backend_factory, skill_read_grant

    def open_session(
        self, settings: SkillsSettings, history, *, catalog_write_guard
    ) -> SkillsSession:
        return SkillsSession(self, settings, history, catalog_write_guard)

    def standalone_routes(self, settings: SkillsSettings) -> dict:
        routes = {}
        for source, root in self._roots(settings):
            if root.is_dir():
                prefix = "/skills/" + source + "/"
                routes[prefix] = SkillBackend(
                    self.backend_factory(root, read_only=True), prefix=prefix
                )
        return routes

    def standalone_extension(self, settings: SkillsSettings) -> SkillsFrameworkExtension:
        sources = tuple(
            "/skills/" + source + "/" for source, root in self._roots(settings) if root.is_dir()
        )
        return SkillsFrameworkExtension(None, None, sources, None)

    @staticmethod
    def _roots(settings: SkillsSettings):
        roots = [
            ("builtin", Path(__file__).parent / "resources"),
            ("user", settings.user_data / "skills"),
        ]
        if settings.project_trusted:
            roots.extend(
                (
                    ("project-agents", settings.workspace / ".agents" / "skills"),
                    ("project", settings.workspace / ".pycode" / "skills"),
                )
            )
        return tuple(roots)

    @staticmethod
    def session_tools(session: SkillsSession, *, read_only=False) -> tuple:
        return (
            (SkillSearch(session.service),)
            if read_only
            else (SkillSearch(session.service), InstallSkill(session.service))
        )

    @staticmethod
    def historical_specs(projection):
        return skill_tool_specs(projection.checkpoint.get("skills", ()))

    @staticmethod
    def selector_required_names() -> tuple[str, ...]:
        return (SkillSearch.spec.name,)
