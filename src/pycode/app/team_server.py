"""Local Worker host; shutdown collects tasks and retains all session/worktree data."""

import uvicorn

from pycode.app.container import (
    build_worker_host,
    issue_worker_host_token,
    revoke_worker_host_token,
)
from pycode.core.errors import DomainError


async def serve_workers(config, providers, port):
    if not 1 <= port <= 65535:
        raise DomainError("invalid_team_port", "Worker port must be between one and 65535")
    protocol, worktrees = build_worker_host(config, providers)
    await worktrees.scan()
    # A fresh token per start; local clients read it from the user data directory.
    token = issue_worker_host_token(config, port)
    server = uvicorn.Server(
        uvicorn.Config(
            protocol.application(token),
            host="127.0.0.1",
            port=port,
            log_level="warning",
            ws="none",
        )
    )
    try:
        await server.serve()
    finally:
        try:
            await protocol.aclose()
        finally:
            revoke_worker_host_token(config, port, token)
