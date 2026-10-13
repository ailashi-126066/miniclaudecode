"""Default background extraction is metered and persists grounded candidates."""

import asyncio
import json
import threading

from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server

from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.core.messages import Message


async def test_default_memory_extract_uses_real_sdk_and_its_own_request_purpose(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    response = chat_completion()
    started, release = threading.Event(), threading.Event()
    response.started, response.release = started, release
    with protocol_server([chat_completion(), response]) as (url, requests):
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data"),
            (provider(url),),
            "s1",
            create=True,
            refresh_models=False,
        )
        try:
            done = await session.run(Message("u1", "user", "Always use pytest."))
            assert done.outcome == "completed"
            assert await asyncio.to_thread(started.wait, 5)
            sources = json.loads(requests[1]["body"]["messages"][1]["content"])
            source = next(item for item in sources if item["role"] == "user")
            proposal = [
                {
                    "action": "add",
                    "key": "tests",
                    "category": "user_preference",
                    "scope": "user",
                    "content": "Always use pytest.",
                    "durable": True,
                    "evidence": [{"entry_id": source["entry_id"], "quote": "Always use pytest."}],
                }
            ]
            response.body = response.body.replace(
                b'"content": "done"', ('"content": ' + json.dumps(json.dumps(proposal))).encode()
            )
            release.set()
            await asyncio.wait_for(session.collect_memory(), 10)
            records = (await session.memory_list())["records"]
            assert len(records) == 1 and records[0]["content"] == "Always use pytest."
            usage = await session.usage()
            assert usage["by_purpose"]["memory_extract"]["request_count"] == 1
            assert usage["session"]["request_count"] == 2
            assert len(requests) == 2
        finally:
            release.set()
            await session.aclose()
