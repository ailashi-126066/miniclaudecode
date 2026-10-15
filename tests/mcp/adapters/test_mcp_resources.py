from pycode.app.bootstrap import compose_application
from pycode.app.config import AppConfig
from pycode.kernel.domain.messages import Message
from tests.mcp.adapters.test_mcp import mcp_config
from tests.skills.adapters.test_skills_http import skill_reply
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server


async def test_resources_and_prompts_are_on_demand_and_use_parent_approval(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await mcp_config(config, mode="dispatch")
    replies = [
        skill_reply("MCPResources", {"server": "srv"}, "list"),
        skill_reply("MCPReadResource", {"server": "srv", "uri": "fixture://note"}, "read"),
        skill_reply(
            "MCPGetPrompt",
            {"server": "srv", "name": "explain", "arguments": {"topic": "context"}},
            "prompt",
        ),
        chat_completion(),
    ]
    with protocol_server(replies) as (url, requests):
        session = await compose_application(config, (provider(url),)).open_session(
            "s1", create=True, refresh_models=False
        )
        try:
            result = await session.run(Message("u1", "user", "resources"))
            assert result.outcome == "needs_input"
            assert "Resource content stays on demand" not in str(requests[0]["body"]["messages"])
            result = await session.resume(
                {"operation_id": result.pending[0]["operation_id"], "decision": "approve"}
            )
            assert result.outcome == "needs_input"
            result = await session.resume(
                {"operation_id": result.pending[0]["operation_id"], "decision": "approve"}
            )
            assert result.outcome == "completed" and len(requests) == 4
            assert "Resource content stays on demand" in str(requests[-1]["body"]["messages"])
            assert "Explain context" in str(requests[-1]["body"]["messages"])
        finally:
            await session.aclose()
