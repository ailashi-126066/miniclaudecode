"""The application's sole public composition root.

Feature modules own their adapters and local lifetimes. This facade selects a module
set and connects application/session/run scopes without exposing their construction.
"""

from dataclasses import dataclass

from pycode import __version__
from pycode.app.composition import memory as memory_scope
from pycode.app.composition import models as model_scope
from pycode.app.composition import runtime as runtime_scope
from pycode.app.composition import session as session_scope
from pycode.app.composition import team as team_scope
from pycode.app.composition import ui as ui_scope
from pycode.app.composition.modules import ApplicationModules, default_modules
from pycode.app.config import AppConfig
from pycode.models import ProviderConfig


@dataclass(frozen=True)
class Application:
    config: AppConfig
    providers: tuple[ProviderConfig, ...]
    modules: ApplicationModules

    def status(self):
        return self.modules.sessions.application(__version__).status()

    async def sandbox_status(self):
        return await self.modules.sandbox.backend().status(self.config.sandbox)

    def sandbox_backend(self):
        return self.modules.sandbox.backend()

    def session_trace_path(self, session_id):
        return self.modules.sessions.layout.sessions / session_id / "entries.jsonl"

    def session_service(self):
        return self.modules.sessions.service()

    def session_repository(self):
        return self.modules.sessions.repository()

    async def open_session(self, session_id, **options):
        return await session_scope.open_agent_session(
            self.config, self.providers, session_id, modules=self.modules, **options
        )

    async def open_view(self, session_id):
        return await session_scope.open_session_view(self.config, session_id, modules=self.modules)

    def model_catalog(self):
        return self.modules.models.catalog(self.providers)

    def model_control(self, store=None, *, catalog=None):
        selected = self.model_catalog() if catalog is None else catalog
        return self.modules.models.control(selected, self.modules.projector(), store)

    def memory_service(self, history, provider=None, consumers=()):
        return memory_scope.build_memory_service(
            self.config, history, provider, consumers, modules=self.modules
        )

    def storage(self, owned_session_id=None):
        return self.modules.sessions.storage(owned_session_id=owned_session_id)

    def worktrees(self):
        return team_scope.build_worktree_manager(self.config, modules=self.modules)

    def console(self, output_format):
        return self.modules.ui.console(output_format)

    def tui(self, session_id=None, tools=()):
        return ui_scope.build_tui(
            self.config, self.providers, session_id=session_id, tools=tools, modules=self.modules
        )

    async def runtime(self, provider, **run_options):
        return await runtime_scope.build_runtime(
            self.config, provider, modules=self.modules, **run_options
        )

    async def background_model(
        self, provider, consumers, session_id, run_id, purpose, **request_options
    ):
        return await model_scope.build_background_model(
            self.config,
            provider,
            consumers,
            session_id,
            run_id,
            purpose,
            modules=self.modules,
            **request_options,
        )

    def memory_updater(self, provider, consumers=()):
        return memory_scope.build_memory_updater(
            self.config, provider, consumers, modules=self.modules
        )

    def summary_workflow(self, model):
        return self.modules.context.workflow(model)


def compose_application(
    config: AppConfig,
    providers: tuple[ProviderConfig, ...] = (),
    *,
    modules: ApplicationModules | None = None,
) -> Application:
    """Compose local capabilities; provider connections open only when requested."""
    return Application(config, providers, default_modules(config) if modules is None else modules)
