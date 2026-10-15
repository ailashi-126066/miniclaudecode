"""Actual parent-process exits around confirmed resource effects and common settlement."""

import asyncio
import json
import os
import sys
from pathlib import Path

from pydantic import TypeAdapter

from pycode.app.composition import open_agent_session
from pycode.app.config import AppConfig
from pycode.kernel.contracts.messages import Message
from pycode.models.domain.models import ProviderConfig


class CrashConsumer:
    def __init__(self, phase):
        self.phase = phase

    async def consume(self, event):
        expected = "tool_execution_end" if self.phase == "ready" else "tool_output_ready"
        if event.kind == expected and event.data.get("name") == "resource_probe":
            os._exit(79)


async def main():
    request = json.load(sys.stdin)
    config = AppConfig(Path(request["workspace"]), Path(request["data"]), memory_mode="off")
    configured = TypeAdapter(ProviderConfig).validate_json(
        json.dumps(request["provider"]), strict=True
    )
    session = await open_agent_session(
        config,
        (configured,),
        "s1",
        create=True,
        refresh_models=False,
        consumers=(CrashConsumer(request["phase"]),),
    )
    try:
        await session.run(Message("u1", "user", "Read and execute resource once"))
        state = await session.inspect()
        await session.resume(
            {"operation_id": state["pending"][0]["operation_id"], "decision": "approve"}
        )
    finally:
        await session.aclose()


if __name__ == "__main__":
    asyncio.run(main())
