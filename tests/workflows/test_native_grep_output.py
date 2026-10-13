"""Large rg output is preserved before native FilesystemMiddleware truncates it."""

from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.messages import Message


async def run_large_grep(tmp_path):
    target = tmp_path / "large.txt"
    target.write_text("needle " + "x" * 100000 + "\n", encoding="utf-8")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server(
        [
            skill_reply(
                "grep",
                {
                    "pattern": "needle",
                    "path": "/workspace/",
                    "glob": "*.txt",
                    "output_mode": "content",
                },
                "grep",
            ),
            chat_completion(),
        ]
    ) as (url, requests):
        session = await open_agent_session(
            config, (provider(url),), "s1", create=True, refresh_models=False
        )
        try:
            done = await session.run(Message("u1", "user", "search large lines"))
            assert done.outcome == "completed" and len(requests) == 2
            result = next(message.tool_result for message in done.messages if message.tool_result)
            assert result.output_id and len(result.text.encode()) <= 50000
            parts = []
            offset, byte_offset = 1, 0
            while True:
                page = await session.read_tool_result(
                    result.output_id, offset=offset, limit=1, byte_offset=byte_offset
                )
                parts.append(page["text"])
                if not page["has_more"]:
                    break
                offset, byte_offset = page["next_offset"], page["next_byte_offset"]
            assert "".join(parts) == "/workspace/large.txt:1:needle " + "x" * 100000
        finally:
            await session.aclose()
