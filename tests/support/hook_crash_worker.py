"""Inject real process exit after a configured command Hook result is confirmed."""

import asyncio
import json
import os
import sys
from pathlib import Path

from pydantic import TypeAdapter

from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.messages import Message
from pycode.core.models import ProviderConfig


async def main():
    request = json.load(sys.stdin)

    class CrashConsumer:
        async def consume(self, event):
            if event.kind == "tool_execution_end" and event.data["name"] == "hook__mark":
                os._exit(81)

    config = AppConfig(Path(request["workspace"]), Path(request["data"]), memory_mode="off")
    session = await open_agent_session(
        config,
        (TypeAdapter(ProviderConfig).validate_json(json.dumps(request["provider"]), strict=True),),
        "s1",
        create=True,
        refresh_models=False,
        consumers=(CrashConsumer(),),
    )
    try:
        await session.run(Message("u1", "user", "Run parent after Hook"))
    finally:
        await session.aclose()


if __name__ == "__main__":
    asyncio.run(main())
