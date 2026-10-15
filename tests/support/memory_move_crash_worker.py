"""Exit a real scope move between its two atomic file publications."""

import asyncio
import os
import sys
from pathlib import Path

from pycode.app.config import AppConfig
from pycode.app.container import build_memory_service, build_session_repository


async def main():
    workspace, boundary = sys.argv[1:]
    root = Path(workspace)
    config = AppConfig(root, root / "data", allow_delegation=False)
    history = await build_session_repository(config).open("s")
    service = build_memory_service(config, history)
    await service.initialize()
    publish = service.moves.publish

    def stop(namespace, state, previous):
        publish(namespace, state, previous)
        if service.scopes[namespace] == boundary:
            os._exit(79)

    service.moves.publish = stop
    try:
        await service.move("stable", "project")
    finally:
        await service.aclose()
        await history.aclose()


if __name__ == "__main__":
    asyncio.run(main())
