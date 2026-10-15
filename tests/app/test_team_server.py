"""`team serve` owns its port before publishing a token and withdraws it on shutdown."""

import asyncio
import socket

import pytest

from pycode.adapters.team.token import issue_token, read_token
from pycode.app import team_server
from pycode.app.config import AppConfig
from pycode.core.errors import DomainError


class FakeProtocol:
    def __init__(self):
        self.closed = False

    def application(self, token):
        async def app(scope, receive, send):
            if scope["type"] == "lifespan":
                while (await receive())["type"] != "lifespan.shutdown":
                    await send({"type": "lifespan.startup.complete"})
                await send({"type": "lifespan.shutdown.complete"})

        return app

    async def aclose(self):
        self.closed = True


class FakeWorktrees:
    async def scan(self):
        return None


@pytest.fixture
def host(tmp_path, monkeypatch):
    protocol = FakeProtocol()
    monkeypatch.setattr(
        team_server, "build_worker_host", lambda config, providers: (protocol, FakeWorktrees())
    )
    return AppConfig(tmp_path, tmp_path / "data"), protocol


async def test_occupied_port_leaves_the_running_hosts_token_untouched(host):
    config, protocol = host
    with socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        occupied.listen()
        port = occupied.getsockname()[1]
        running = issue_token(config.user_data, port)
        with pytest.raises(DomainError) as error:
            await team_server.serve_workers(config, None, port)
    assert error.value.code == "team_port_unavailable"
    assert read_token(config.user_data, port) == running
    assert protocol.closed


async def test_token_is_published_while_serving_and_removed_on_shutdown(host):
    config, protocol = host
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    serving = asyncio.create_task(team_server.serve_workers(config, None, port))
    async with asyncio.timeout(10):
        while read_token(config.user_data, port) is None:
            assert not serving.done()
            await asyncio.sleep(0.01)
    serving.cancel()
    with pytest.raises(asyncio.CancelledError):
        await serving
    assert read_token(config.user_data, port) is None and protocol.closed
