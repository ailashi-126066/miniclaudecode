"""Persistence and critical Hooks stop a run and collect its other live tools."""

import asyncio
import json

import pytest
from langchain_core.messages import AIMessage
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_agent import assemble, tool_response
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.errors import StorageError
from pycode.core.messages import Message, ToolResult
from pycode.services.tool_executor import ToolExecutor
from pycode.workflows.tool_errors import execute_tool_errors


async def test_result_write_failure_collects_other_parallel_tool_before_return():
    runtime, context, model, original, journal, _ = assemble(
        [tool_response(two=True), AIMessage(id="done", content="done")]
    )
    started = asyncio.Event()
    cleaned = asyncio.Event()

    class Reader:
        spec = original.spec

        async def execute(self, current, arguments):
            if arguments["value"] == "first":
                await started.wait()
                return ToolResult("", "", "read completed")
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cleaned.set()

    append = journal.append_message

    async def fail_result(message):
        if message.role == "tool":
            raise StorageError("forced_write_failure", "Result persistence failed")
        return await append(message)

    journal.append_message = fail_result
    context.registry.replace_source("builtin", (Reader(),))
    context.executor = ToolExecutor(
        context.registry,
        context.executor.validator,
        context.executor.gate,
        context.events,
        error_boundary=execute_tool_errors,
    )
    with pytest.raises(StorageError, match="persistence failed"):
        await asyncio.wait_for(runtime.run((Message("u", "user", "read both"),), context), 5)
    assert cleaned.is_set()
    assert model.call_index == 1
    assert not context.tool_tasks


async def test_real_failing_pretool_hook_is_paired_and_stops_before_next_model(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    (tmp_path / "code.py").write_text("original")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    config.user_data.mkdir()
    (config.user_data / "hooks.json").write_text(
        json.dumps(
            [
                {
                    "id": "failure",
                    "event": "pre_tool_use",
                    "action": {"type": "command", "command": "exit 7"},
                }
            ]
        ),
        encoding="utf-8",
    )
    with protocol_server(
        [skill_reply("read_file", {"file_path": "/workspace/code.py"}, "read"), chat_completion()]
    ) as (url, requests):
        session = await open_agent_session(
            config, (provider(url),), "s", create=True, refresh_models=False
        )
        try:
            with pytest.raises(StorageError) as error:
                await session.run(Message("u", "user", "read"))
            assert error.value.code == "critical_hook_failed"
            assert len(requests) == 1
            state = await session.inspect()
            assert state["pending_run_id"] is None
            results = [
                entry["message"]["tool_result"]
                for entry in state["snapshot"]["entries"]
                if entry["message"] is not None and entry["message"]["tool_result"] is not None
            ]
            assert len(results) == 1 and results[0]["error_code"] == "hook_failed"
            assert (await session.run(Message("next", "user", "continue"))).outcome == "completed"
            assert len(requests) == 2
        finally:
            await session.aclose()
