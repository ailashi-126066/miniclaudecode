"""The real session entry owns initial recall and independent background schema billing."""

import json
import threading

from tests.services.test_memory_evidence import proposal
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.messages import Message


async def test_default_json_memory_uses_session_entry_sdk_and_separate_request_purpose(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {}
    ready = threading.Event()

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        if "MemoryFeedbackChoices" in names:
            return skill_reply("MemoryFeedbackChoices", {"feedback": []}, "feedback")
        if "MemoryEvidenceVerdict" in names:
            return skill_reply(
                "MemoryEvidenceVerdict",
                {
                    "supported": True,
                    "durable": True,
                    "scope_valid": True,
                    "reason": "actual user preference",
                },
                "review",
            )
        if "SemanticMemory" in names:
            if "for the project scope" in json.dumps(request["body"]["messages"]):
                return chat_completion()
            if not ready.wait(2):
                raise AssertionError("Source snapshot was not prepared")
            snapshot = holder["snapshot"]
            entry = next(
                e for e in snapshot.entries if e.message is not None and e.message.role == "user"
            )
            data = proposal(entry.id)
            data["content"]["sources"][0]["session_id"] = "s1"
            return skill_reply("SemanticMemory", data["content"], "extract")
        return chat_completion()

    with protocol_server(respond) as (url, requests):
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", allow_delegation=False),
            (provider(url),),
            "s1",
            create=True,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        try:
            done = await session.run(Message("u1", "user", "Always use pytest."))
            assert done.outcome == "completed"
            from pycode.app.container import build_session_repository

            reader = await build_session_repository(AppConfig(tmp_path, tmp_path / "data")).open(
                "s1"
            )
            try:
                holder["snapshot"] = await reader.snapshot()
                ready.set()
            finally:
                await reader.aclose()
            await session.collect_memory()
            records = (await session.memory_list())["records"]
            assert len(records) == 1 and records[0]["content"] == "Always use pytest."
            usage = await session.usage()
            assert usage["by_purpose"]["memory_extract"]["request_count"] == 4
            assert usage["by_purpose"]["agent"]["request_count"] == 1
            assert len(requests) == 5
        finally:
            ready.set()
            await session.aclose()
