import ast
import json

from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server

from pycode.app.config import AppConfig
from pycode.app.container import build_session_repository, open_agent_session
from pycode.core.messages import Message


def search_response():
    response = chat_completion(tool=True)
    rows = []
    for line in response.body.decode().splitlines():
        if line.startswith("data: {"):
            data = json.loads(line[6:])
            for choice in data.get("choices", []):
                if "tool_calls" in choice["delta"]:
                    choice["delta"]["tool_calls"] = [
                        {
                            "index": 0,
                            "id": "glob-first",
                            "type": "function",
                            "function": {
                                "name": "glob",
                                "arguments": json.dumps({"pattern": "/workspace/**/*.py"}),
                            },
                        },
                        {
                            "index": 1,
                            "id": "grep-second",
                            "type": "function",
                            "function": {
                                "name": "grep",
                                "arguments": json.dumps(
                                    {
                                        "pattern": "needle",
                                        "glob": "*.py",
                                        "path": "/workspace/",
                                        "output_mode": "content",
                                    }
                                ),
                            },
                        },
                    ]
            rows.append("data: " + json.dumps(data) + "\n\n")
        elif line == "data: [DONE]":
            rows.append("data: [DONE]\n\n")
    response.body = "".join(rows).encode()
    return response


async def test_public_agent_executes_parallel_read_searches_without_approval_and_pairs_outputs(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    (tmp_path / "one.py").write_text("needle one\n", encoding="utf-8")
    (tmp_path / "nested").mkdir()
    (tmp_path / "nested/你好.py").write_text("needle two\n", encoding="utf-8")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([search_response(), chat_completion()]) as (url, requests):
        session = await open_agent_session(
            config, (provider(url),), "s1", create=True, refresh_models=False
        )
        try:
            result = await session.run(Message("u1", "user", "search project"))
            assert result.outcome == "completed" and len(requests) == 2
            names = {item["function"]["name"] for item in requests[0]["body"]["tools"]}
            assert {"read_file", "write_file", "edit_file", "Bash", "grep", "glob"} <= names
            outputs = {
                message.tool_result.call_id: message.tool_result
                for message in result.messages
                if message.tool_result
            }
            assert set(outputs) == {"glob-first", "grep-second"}
            assert ast.literal_eval(outputs["glob-first"].text) == [
                "/workspace/nested/你好.py",
                "/workspace/one.py",
            ]
            assert (
                "/workspace/one.py:\n  1: needle one" in outputs["grep-second"].text
                and "/workspace/nested/你好.py:\n  1: needle two" in outputs["grep-second"].text
            )
            assert all(not item.is_error and item.operation_id for item in outputs.values())
            assert (
                await session.run(Message("u1", "user", "search project"))
            ).outcome == "completed"
            assert len(requests) == 2
        finally:
            await session.aclose()
    reader = await build_session_repository(config).open("s1")
    try:
        snapshot = await reader.snapshot()
        assert (
            sum(
                entry.message is not None and entry.message.role == "tool"
                for entry in snapshot.entries
            )
            == 2
        )
    finally:
        await reader.aclose()
