"""Local Worker host; shutdown collects tasks and retains all session/worktree data."""

import uvicorn

from pycode.app.container import build_worker_host
from pycode.core.errors import DomainError


async def serve_workers(config, providers, port):
    if not 1 <= port <= 65535:
        raise DomainError("invalid_team_port", "Worker port must be between one and 65535")
    protocol, worktrees = build_worker_host(config, providers)
    await worktrees.scan()
    server = uvicorn.Server(
        uvicorn.Config(
            protocol.application(),
            host="127.0.0.1",
            port=port,
            log_level="warning",
            ws="none",
        )
    )
    try:
        await server.serve()
    finally:
        await protocol.aclose()
