"""Connect the selected presentation module to application session operations."""

from pycode.app.composition.modules import default_modules
from pycode.app.composition.session import open_agent_session


class ConfiguredInteractiveSessions:
    def __init__(self, config, providers, tools=(), *, modules):
        self._config, self._providers, self._tools = config, providers, tools
        self._modules = modules
        self._sessions = modules.sessions.service()

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
            modules=self._modules,
        )

    async def list_sessions(self):
        return await self._sessions.list_sessions()

    async def sandbox_status(self):
        return await self._modules.sandbox.backend().status(self._config.sandbox)

    async def fork(self, source_id, leaf_id, target_id):
        return await self._sessions.fork(source_id, leaf_id, target_id)


def build_tui(config, providers, *, session_id=None, tools=(), modules=None):
    modules = default_modules(config) if modules is None else modules
    sessions = ConfiguredInteractiveSessions(config, providers, tools, modules=modules)
    return modules.ui.tui(sessions, session_id=session_id)
