"""Composition of ui capabilities; concrete adapters stay at this boundary."""

import sys

from pycode.app.composition.capabilities import build_sandbox
from pycode.app.composition.session import build_session_service, open_agent_session
from pycode.ui.adapters.console import ConsoleOutput
from pycode.ui.adapters.tui import PyApp


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


async def sandbox_status(config):
    return await build_sandbox().status(config.sandbox)
