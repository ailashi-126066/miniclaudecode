"""The actual BaseChatModel extraction request is metered and cannot execute tools."""

import json

from langchain_core.messages import AIMessage
from tests.services.test_memory import setup_memory
from tests.support.model import ScriptedChatModel
from tests.support.requests import MemoryRequestStore, TestTiming

from pycode.adapters.models.failures import ProviderFailureClassifier
from pycode.core.messages import Message
from pycode.core.models import ModelSelection
from pycode.core.requests import RetryPolicy
from pycode.core.session import EntryDraft
from pycode.services.context_projector import ContextProjector
from pycode.services.events import EventStream
from pycode.workflows.memory import MemoryWorkflow
from pycode.workflows.model_requests import ModelRequestRunner


async def test_real_memory_request_records_purpose_and_injects_approved_snapshot(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path)
    source = await history.snapshot()
    values = [
        {
            "action": "add",
            "key": "test-framework",
            "category": "user_preference",
            "scope": "user",
            "content": "Always use pytest.",
            "durable": True,
            "evidence": [{"entry_id": entry_id, "quote": "Always use pytest."}],
        }
    ]
    model = ScriptedChatModel(
        responses=[
            AIMessage(
                content=json.dumps(values),
                usage_metadata={"input_tokens": 10, "output_tokens": 20, "total_tokens": 30},
            )
        ]
    )
    records = MemoryRequestStore()
    timing = TestTiming()
    runner = ModelRequestRunner(
        records,
        ProviderFailureClassifier(timing),
        timing,
        RetryPolicy(),
        EventStream("s1", "r1", ()),
        ModelSelection("test", "fixed", "openai"),
        "s1",
        "r1",
        purpose="memory_extract",
    )
    try:
        candidates = await MemoryWorkflow(model, runner).extract(source, "r1")
        assert len(model.requests) == 1
        assert (await records.list_requests())[0].purpose == "memory_extract"
        assert (await records.list_requests())[0].usage.total_tokens == 30
        await service.ingest(candidates, source, model="fixed")
        await service.select("pytest")
        projected = ContextProjector().project(await history.snapshot())
        memory_messages = [
            message
            for message in projected.messages
            if message.provider_metadata.get("source_type") == "memory_load"
        ]
        assert len(memory_messages) == 1 and "Always use pytest." in memory_messages[0].text
        raw = (await history.snapshot()).entries
        assert any(entry.type == "memory_load" for entry in raw)
        await service.set_mode("off")
        assert not any(
            message.provider_metadata.get("source_type") == "memory_load"
            for message in ContextProjector().project(await history.snapshot()).messages
        )
    finally:
        await history.aclose()


async def test_background_extraction_has_one_owner_per_settled_run(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path)

    class Extractor:
        model_id = "deterministic"
        calls = 0

        async def extract(self, snapshot, run_id):
            self.calls += 1
            return ()

    extractor = Extractor()
    service.extractor = extractor
    try:
        await service.settled("r1")
        await service.settled("r1")
        await service.tasks["r1"]
        assert extractor.calls == 1
        entries = (await history.snapshot()).entries
        statuses = [entry.data["status"] for entry in entries if entry.type == "memory_extract"]
        assert statuses == ["started", "completed"]
    finally:
        await service.aclose()
        await history.aclose()


async def test_memory_request_uses_only_messages_after_last_completed_extraction(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path)

    class Requests:
        def __init__(self):
            self.inputs = []

        async def invoke(self, request, handler, logical_id):
            self.inputs.append(json.loads(request.messages[-1].content))
            return await handler(request)

    requests = Requests()
    model = ScriptedChatModel(responses=[AIMessage(content="[]")])
    workflow = MemoryWorkflow(model, requests)
    try:
        await history.append_entry(
            EntryDraft(
                "memory_extract",
                data={
                    "status": "completed",
                    "source_leaf_id": entry_id,
                },
            )
        )
        assert await workflow.extract(await history.snapshot(), "empty") == ()
        assert requests.inputs == []
        new_entry = await history.append_message(Message("u2", "user", "Use plain functions."))
        assert await workflow.extract(await history.snapshot(), "next") == ()
        assert requests.inputs == [
            [{"entry_id": new_entry, "role": "user", "text": "Use plain functions."}]
        ]
    finally:
        await history.aclose()
