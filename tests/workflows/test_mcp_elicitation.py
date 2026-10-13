"""The native MCPAdapter interrupt resumes through the same live Agent graph."""

import pytest
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_mcp import mcp_config
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.errors import StorageError
from pycode.core.messages import Message


@pytest.mark.parametrize("action", ["accept", "decline", "cancel"])
async def test_real_mcp_form_interrupt_answer_and_single_result(tmp_path, monkeypatch, action):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await mcp_config(config)
    with protocol_server([skill_reply("mcp__srv__confirm", {}, "c1"), chat_completion()]) as (
        url,
        requests,
    ):
        session = await open_agent_session(
            config, (provider(url),), "s1", create=True, refresh_models=False
        )
        try:
            pending = await session.run(Message("u1", "user", "Ask MCP"))
            assert pending.outcome == "needs_input" and len(requests) == 1
            question = pending.pending[0]
            assert question["kind"] == "mcp_elicitation"
            answer = {"action": action}
            if action == "accept":
                with pytest.raises(StorageError, match="requested schema"):
                    await session.resume(
                        {
                            "responses": {
                                question["requests"][0]["key"]: {
                                    "action": "accept",
                                    "content": {"value": 42},
                                }
                            }
                        }
                    )
                assert len(requests) == 1
                answer["content"] = {"value": "human"}
            resumed = await session.resume({"responses": {question["requests"][0]["key"]: answer}})
            assert resumed.outcome == "completed" and len(requests) == 2
            snapshot = (await session.inspect())["snapshot"]
            results = []
            questions = []
            for entry in snapshot["entries"]:
                if entry["type"] == "mcp_elicitation":
                    questions.append(entry)
                if entry["message"] and entry["message"]["role"] == "tool":
                    results.append(entry["message"]["tool_result"])
            assert len(questions) == 1 and len(results) == 1
            assert results[0]["text"] == ("accepted:human" if action == "accept" else action)
        finally:
            await session.aclose()
