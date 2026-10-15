"""Native fork delegation preserves its context and never activates the parent."""

import asyncio

import pytest

from pycode.app.composition import open_agent_session
from pycode.app.config import AppConfig
from pycode.kernel.contracts.messages import Message
from tests.skills.adapters.test_skills_http import skill_reply
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server


@pytest.mark.parametrize("context", ["full", "recent", "none"])
async def test_fork_skill_native_task_has_own_history_and_loaded_state(
    tmp_path, monkeypatch, context
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    skill = config.user_data / "skills" / "reviewer" / "SKILL.md"

    def write():
        skill.parent.mkdir(parents=True)
        skill.write_text(
            "---\nname: reviewer\ndescription: Read-only review\nmode: fork\n"
            + f"context: {context}\nallowed-tools: [Read]\n---\nPRIVATE FORK INSTRUCTIONS",
            encoding="utf-8",
        )

    await asyncio.to_thread(write)
    replies = [
        chat_completion(),
        skill_reply(
            "task", {"description": "Review the project", "subagent_type": "skill-reviewer"}, "fork"
        ),
        skill_reply("read_file", {"file_path": "/skills/user/reviewer/SKILL.md"}, "child-load"),
        chat_completion(),
        chat_completion(),
    ]
    with protocol_server(replies) as (url, requests):
        session = await open_agent_session(
            config, (provider(url),), "lead", create=True, refresh_models=False
        )
        try:
            await session.run(Message("old", "user", "OLD CONTEXT"))
            result = await session.load_skill("reviewer", "CURRENT TASK")
            assert result.outcome == "completed" and len(requests) == 5
            state = await session.inspect()
            assert not any(entry["type"] == "skill_load" for entry in state["snapshot"]["entries"])
            child = next(
                entry["data"]["session_id"]
                for entry in state["snapshot"]["entries"]
                if entry["type"] == "team_agent"
            )
            first_child = str(requests[2]["body"]["messages"])
            assert ("OLD CONTEXT" in first_child) is (context == "full")
            assert "PRIVATE FORK INSTRUCTIONS" not in first_child
            assert "PRIVATE FORK INSTRUCTIONS" in str(requests[3]["body"]["messages"])
            assert "PRIVATE FORK INSTRUCTIONS" not in str(requests[4]["body"]["messages"])
        finally:
            await session.aclose()
        restored = await open_agent_session(config, (provider(url),), child, refresh_models=False)
        try:
            state = await restored.inspect()
            loads = [
                entry for entry in state["snapshot"]["entries"] if entry["type"] == "skill_load"
            ]
            assert len(loads) == 1 and loads[0]["data"]["name"] == "reviewer"
        finally:
            await restored.aclose()
