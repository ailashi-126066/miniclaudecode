"""Evidence validation, safe auto application, conflicts and bounded retrieval."""

import pytest
from tests.support.requests import TestTiming

from pycode.adapters.storage.memory import FileMemoryStore
from pycode.adapters.storage.session import JsonlSessionStore
from pycode.core.messages import Message
from pycode.core.session import SessionHeader
from pycode.services.memory import MemoryService


async def setup_memory(tmp_path, mode="auto"):
    timing = TestTiming()
    history = JsonlSessionStore(
        tmp_path / "session", SessionHeader("s1", str(tmp_path), "t"), timing
    )
    entry_id = await history.append_message(
        Message(
            "u1", "user", "Always use pytest. Keep functions readable. 本次任务仅使用临时目录。"
        )
    )
    store = FileMemoryStore(tmp_path / "user", tmp_path / "project")
    service = MemoryService(history, store, timing, mode=mode, max_selected=2)
    return history, store, service, entry_id


def candidate(entry_id, content="Always use pytest.", key="test-framework"):
    return {
        "action": "add",
        "key": key,
        "category": "user_preference",
        "scope": "user",
        "content": content,
        "evidence": [{"entry_id": entry_id, "quote": "Always use pytest."}],
    }


async def test_explicit_memory_edit_keeps_identity_and_real_user_evidence(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path)
    try:
        identifier = (await service.ingest((candidate(entry_id),), await history.snapshot()))[0]
        edited = await service.edit(identifier, "Use pytest and plain functions.")
        assert edited == identifier
        record = await store.read_record(identifier)
        assert record.content == "Use pytest and plain functions."
        evidence = record.evidence[0]
        source = (await history.snapshot()).index[evidence["entry_id"]]
        assert source.type == "memory_edit" and source.message.role == "user"
        assert evidence["quote"] == source.message.text
    finally:
        await history.aclose()


async def test_safe_memory_auto_applies_and_duplicates_do_not_create_new_records(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path)
    try:
        source = await history.snapshot()
        accepted = await service.ingest((candidate(entry_id),), source)
        assert len(accepted) == 1
        records = await store.list_records()
        assert len(records) == 1 and records[0].scope == "user"
        assert records[0].content == "Always use pytest."
        duplicate = await service.ingest((candidate(entry_id),), source)
        assert duplicate == accepted and len(await store.list_records()) == 1
        assert (tmp_path / "user" / "MEMORY.md").is_file()
    finally:
        await history.aclose()


async def test_conflicting_update_waits_for_explicit_review_and_preserves_identity(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path)
    try:
        initial = await service.ingest((candidate(entry_id),), await history.snapshot())
        correction = await history.append_message(
            Message("u2", "user", "Prefer unittest instead of pytest.")
        )
        value = {
            **candidate(correction),
            "action": "update",
            "target_id": initial[0],
            "content": "Prefer unittest.",
            "evidence": [{"entry_id": correction, "quote": "Prefer unittest instead of pytest."}],
        }
        assert await service.ingest((value,), await history.snapshot()) == ()
        assert (await store.list_records())[0].content == "Always use pytest."
        pending = next(item for item in await store.candidates() if item.status == "pending")
        assert pending.reason == "conflict_requires_review"
        updated = await service.review(pending.candidate_id, "approve")
        assert updated == initial[0]
        assert (await store.list_records())[0].content == "Prefer unittest."
    finally:
        await history.aclose()


@pytest.mark.parametrize("failure", ["missing", "quote", "temporary", "secret", "category"])
async def test_invalid_candidates_never_enter_memory_review(tmp_path, failure):
    history, store, service, entry_id = await setup_memory(tmp_path)
    try:
        value = candidate(entry_id)
        if failure == "missing":
            value["evidence"] = []
        elif failure == "quote":
            value["evidence"] = [{"entry_id": entry_id, "quote": "not in conversation"}]
        elif failure == "temporary":
            value["content"] = "本次任务必须使用 pytest"
        elif failure == "secret":
            value["content"] = "api_key=private-secret-key"
        else:
            value["category"] = "other"
        assert await service.ingest((value,), await history.snapshot()) == ()
        assert await store.candidates() == () and await store.list_records() == ()
    finally:
        await history.aclose()


async def test_review_mode_and_scope_adjustment_are_explicit_and_selection_is_bounded(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path, "review")
    try:
        await service.ingest((candidate(entry_id),), await history.snapshot())
        pending = (await store.candidates())[0]
        assert await store.list_records() == ()
        await service.review(pending.candidate_id, "approve", scope="project")
        records = await store.list_records()
        assert records[0].scope == "project"
        selected = await service.select("pytest tests")
        assert len(selected) == 1 and selected[0].memory_id == records[0].memory_id
        assert (await history.snapshot()).entries[-1].type == "memory_load"
        await service.set_mode("off")
        assert await service.select("pytest") == ()
        assert (await history.snapshot()).entries[-1].data["records"] == ()
    finally:
        await history.aclose()


async def test_reviewed_removal_keeps_change_records_and_no_longer_injects(tmp_path):
    history, store, service, entry_id = await setup_memory(tmp_path)
    try:
        identifier = (await service.ingest((candidate(entry_id),), await history.snapshot()))[0]
        value = {**candidate(entry_id), "action": "remove", "target_id": identifier}
        assert await service.ingest((value,), await history.snapshot()) == ()
        pending = next(item for item in await store.candidates() if item.status == "pending")
        await service.review(pending.candidate_id, "approve")
        assert await store.list_records() == ()
        assert (tmp_path / "user" / "removed" / identifier / "content.md").is_file()
    finally:
        await history.aclose()
