"""Local Worker host; shutdown collects tasks and retains all session/worktree data."""

import socket

import uvicorn

from pycode.app.container import (
    build_worker_host,
    issue_worker_host_token,
    revoke_worker_host_token,
)
from pycode.core.errors import DomainError


def bind_listener(port):
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))
        listener.listen(2048)
        listener.setblocking(False)
    except OSError as error:
        listener.close()
        raise DomainError(
            "team_port_unavailable", f"Worker port {port} is unavailable: {error.strerror}"
        ) from error
    return listener


async def serve_workers(config, providers, port):
    if not 1 <= port <= 65535:
        raise DomainError("invalid_team_port", "Worker port must be between one and 65535")
    protocol, worktrees = build_worker_host(config, providers)
    await worktrees.scan()
    try:
        # Own the port before publishing, so a duplicate start cannot replace a live host's token.
        listener = bind_listener(port)
        try:
            # A fresh token per start; local clients read it from the user data directory.
            token = issue_worker_host_token(config, port)
            try:
                server = uvicorn.Server(
                    uvicorn.Config(protocol.application(token), log_level="warning", ws="none")
                )
                await server.serve(sockets=[listener])
            finally:
                revoke_worker_host_token(config, port, token)
        finally:
            listener.close()
    finally:
        await protocol.aclose()
