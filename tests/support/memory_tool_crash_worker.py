"""Exit the real main graph after memory publication or output/result persistence."""

import asyncio
import os
import sys
from dataclasses import replace
from pathlib import Path

from pycode.adapters.storage.session import JsonlSessionStore
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.messages import Message
from pycode.services.memory_commit import MemoryCommitService
from tests.support.execution import provider


async def main():
    workspace, url, boundary, action = sys.argv[1:]
    root = Path(workspace)
    commit = MemoryCommitService.commit
    prepare = FileToolOutputStore.prepare
    append = JsonlSessionStore.append_message

    async def crash_commit(self, *args, **kwargs):
        result = await commit(self, *args, **kwargs)
        if boundary == "published" and kwargs.get("tool_receipt") is not None:
            os._exit(76)
        return result

    async def crash_prepare(self, context, result, mode):
        saved = await prepare(self, context, result, mode)
        if boundary == "output_ready" and context.tool_name == "manage_user_memory":
            os._exit(76)
        return saved

    async def crash_append(self, message):
        entry_id = await append(self, message)
        if (
            boundary == "result_saved"
            and message.tool_result is not None
            and message.tool_result.name == "manage_user_memory"
        ):
            os._exit(76)
        return entry_id

    MemoryCommitService.commit = crash_commit
    FileToolOutputStore.prepare = crash_prepare
    JsonlSessionStore.append_message = crash_append
    configured = provider(url)
    configured = replace(configured, tool_selector=replace(configured.tool_selector, enabled=False))
    session = await open_agent_session(
        AppConfig(root, root / "data", allow_delegation=False),
        (configured,),
        "s1",
        create=True,
        refresh_models=False,
        startup_hooks=False,
        connect_mcp=False,
    )
    text = {
        "create": "Always use pytest.",
        "update": "Always use pytest with readable tests.",
        "delete": "Forget saved memory 11111111111111111111111111111111 permanently.",
    }[action]
    try:
        await session.run(Message("u", "user", text))
    finally:
        await session.aclose()


if __name__ == "__main__":
    asyncio.run(main())
