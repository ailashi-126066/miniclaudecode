"""Configured Agent Hooks return real child results without recursive delegation."""

import pytest
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_hooks import hook_file
from tests.workflows.test_team import finished

from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.messages import Message


@pytest.mark.parametrize("event", ["pre_send", "startup"])
async def test_agent_hook_runs_actual_readonly_child_and_persists_its_real_result(
    tmp_path, monkeypatch, event
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await hook_file(
        config,
        [
            {
                "id": "review",
                "event": event,
                "once": True,
                "action": {"type": "agent", "prompt": "Inspect current task", "timeout": 10},
            }
        ],
    )
    with protocol_server([finished("REAL HOOK CHILD RESULT"), chat_completion()]) as (
        url,
        requests,
    ):
        session = await open_agent_session(
            config, (provider(url),), "s1", create=True, refresh_models=False
        )
        try:
            result = await session.run(Message("u1", "user", "task"))
            assert result.outcome == "completed" and len(requests) == 2
            state = await session.inspect()
            hooks = [
                entry for entry in state["snapshot"]["entries"] if entry["type"] == "hook_result"
            ]
            assert len(hooks) == 1
            assert hooks[0]["data"]["result"]["text"] == "REAL HOOK CHILD RESULT"
            assert not hooks[0]["data"]["result"]["is_error"]
            assert (await session.usage())["team"]["request_count"] == 1
        finally:
            await session.aclose()
