"""SkillsMiddleware disclosure and per-execution Python resource approval."""

import pytest

from pycode.app.composition import open_agent_session, sandbox_status
from pycode.app.config import AppConfig
from pycode.kernel.contracts.messages import Message
from pycode.kernel.values import plain_data
from tests.skills.adapters.test_skills_http import skill_reply
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.support.skill_resources import write_resource_skill


@pytest.mark.parametrize("kind", ["sync", "async"])
async def test_skill_is_framework_loaded_and_python_requires_each_approval(
    tmp_path, monkeypatch, kind
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await write_resource_skill(config.user_data / "skills", kind=kind)
    replies = [
        skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "load-1"),
        skill_reply("resource_probe", {"detail": "first"}, "python-1"),
        skill_reply("resource_probe", {"detail": "second"}, "python-2"),
        chat_completion(),
    ]
    with protocol_server(replies) as (url, requests):
        session = await open_agent_session(
            config, (provider(url),), "s1", create=True, refresh_models=False
        )
        try:
            waiting = await session.run(Message("u1", "user", "Use resource skill"))
            assert waiting.outcome == "needs_input"
            assert waiting.pending[0]["call"]["name"] == "resource_probe"
            assert not (tmp_path / "imported.txt").exists()
            assert "Resource instructions" not in str(requests[0]["body"]["messages"])
            assert "resource_probe" not in str(requests[0]["body"]["tools"])
            assert "Resource instructions" in str(requests[1]["body"]["messages"])
            assert "resource_probe" in str(requests[1]["body"]["tools"])
            details = waiting.pending[0]["approval_details"]
            assert details["skill"] == "resource" and details["script_sha256"]
            reported_sandbox = waiting.pending[0]["sandbox"]
            assert reported_sandbox == plain_data(await sandbox_status(config))
            assert reported_sandbox["enabled"] is False
            assert reported_sandbox["auto_approve"] is False
            first = await session.resume(
                {"operation_id": waiting.pending[0]["operation_id"], "decision": "approve"}
            )
            assert first.outcome == "needs_input"
            assert first.pending[0]["call"]["name"] == "resource_probe"
            assert first.pending[0]["operation_id"] != waiting.pending[0]["operation_id"]
            assert (tmp_path / "imported.txt").exists()
            done = await session.resume(
                {"operation_id": first.pending[0]["operation_id"], "decision": "deny"}
            )
            assert done.outcome == "completed" and len(requests) == 4
            state = await session.inspect()
            loads = [
                entry for entry in state["snapshot"]["entries"] if entry["type"] == "skill_load"
            ]
            assert len(loads) == 1 and loads[0]["data"]["message_entry_id"]
            assert "text" not in loads[0]["data"] and "raw" not in loads[0]["data"]
            results = [
                entry["message"]["tool_result"]
                for entry in state["snapshot"]["entries"]
                if entry["message"] and entry["message"]["tool_result"]
            ]
            assert len(results) == 3
            assert results[0]["details"]["protected_content"]
            assert not results[1]["is_error"]
            assert results[2]["error_code"] == "permission_denied"
        finally:
            await session.aclose()
