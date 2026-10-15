import json
from dataclasses import replace

import pytest

from pycode.context.services.context_projector import ContextProjector
from pycode.kernel import ModelSelection
from pycode.kernel.contracts.history import EntryDraft, SessionHeader
from pycode.kernel.contracts.messages import Message
from pycode.kernel.errors import StorageError
from pycode.session.adapters.storage.session import JsonlSessionStore
from tests.support.requests import TestTiming


def open_store(tmp_path):
    return JsonlSessionStore(
        tmp_path / "session", SessionHeader("s1", "/project", "created"), TestTiming()
    )


async def test_setup_is_lazy_and_first_user_publishes_header_setup_and_input(tmp_path):
    store = open_store(tmp_path)
    assert (await store.snapshot()).entries == ()
    assert not (tmp_path / "session").exists()
    selection = ModelSelection("configured", "model", "openai")
    await store.append_entry(EntryDraft("model_change", selection=selection))
    assert not (tmp_path / "session/entries.jsonl").exists()
    user_id = await store.append_message(Message("input-1", "user", "hello"))
    snapshot = await store.snapshot()
    assert snapshot.leaf_id == user_id
    lines = (tmp_path / "session/entries.jsonl").read_text().splitlines()
    assert [json.loads(line).get("type", "header") for line in lines] == [
        "header",
        "model_change",
        "message",
    ]
    await store.aclose()
    restored = open_store(tmp_path)
    try:
        projection = ContextProjector().project(await restored.snapshot())
        assert projection.selection == selection
        assert projection.messages[0].text == "hello"
    finally:
        await restored.aclose()


async def test_append_preserves_prefix_and_branch_position_survives_reopen(tmp_path):
    store = open_store(tmp_path)
    root = await store.append_message(Message("u1", "user", "start"))
    old = (tmp_path / "session/entries.jsonl").read_bytes()
    await store.append_message(Message("a1", "assistant", "branch A"))
    assert (tmp_path / "session/entries.jsonl").read_bytes().startswith(old)
    await store.branch(root)
    new = await store.append_message(Message("a2", "assistant", "branch B"))
    await store.aclose()
    restored = open_store(tmp_path)
    try:
        snapshot = await restored.snapshot()
        assert snapshot.leaf_id == new
        assert len(snapshot.entries) == 3
        assert [message.text for message in ContextProjector().project(snapshot).messages] == [
            "start",
            "branch B",
        ]
        await restored.branch(root)
    finally:
        await restored.aclose()
    again = open_store(tmp_path)
    try:
        assert (await again.snapshot()).leaf_id == root
    finally:
        await again.aclose()


async def test_input_id_retry_does_not_append_duplicate_or_move_leaf(tmp_path):
    store = open_store(tmp_path)
    user = Message("u1", "user", "hello")
    first = await store.append_message(user)
    last = await store.append_message(Message("a1", "assistant", "reply"))
    assert await store.append_message(user) == first
    assert (await store.snapshot()).leaf_id == last
    with pytest.raises(StorageError, match="different content"):
        await store.append_message(replace(user, text="changed"))
    await store.aclose()


async def test_two_store_instances_cannot_write_same_session(tmp_path):
    first = open_store(tmp_path)
    await first.append_message(Message("u1", "user", "hello"))
    second = open_store(tmp_path)
    with pytest.raises(StorageError) as error:
        await second.append_message(Message("u2", "user", "must not be written"))
    assert error.value.code == "session_busy"
    await first.aclose()
    await second.append_message(Message("u2", "user", "now allowed"))
    assert len((await second.snapshot()).entries) == 2
    await second.aclose()


async def test_partial_tail_is_preserved_before_repair_and_append(tmp_path):
    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "hello"))
    await store.aclose()
    path = tmp_path / "session/entries.jsonl"
    with path.open("ab") as output:
        output.write(b'{"id":"partial')
    damaged = path.read_bytes()
    restored = open_store(tmp_path)
    snapshot = await restored.snapshot()
    assert "partial_tail" in snapshot.issues
    assert len(snapshot.entries) == 1
    assert path.read_bytes() == damaged
    await restored.append_message(Message("u2", "user", "continued"))
    backups = list((tmp_path / "session/recovery").glob("*.jsonl"))
    assert len(backups) == 1
    assert backups[0].read_bytes() == damaged
    assert len((await restored.snapshot()).entries) == 2
    await restored.aclose()


async def test_log_position_commit_gap_adopts_only_a_child_of_recorded_leaf(tmp_path):
    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "hello"))
    old_position = (tmp_path / "session/position.json").read_bytes()
    new_leaf = await store.append_message(Message("a1", "assistant", "reply"))
    await store.aclose()
    (tmp_path / "session/position.json").write_bytes(old_position)
    restored = open_store(tmp_path)
    try:
        snapshot = await restored.snapshot()
        assert snapshot.leaf_id == new_leaf
        assert "position_after_append_gap" in snapshot.issues
    finally:
        await restored.aclose()


async def test_unknown_entry_is_kept_but_not_sent_to_model(tmp_path):
    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "hello"))
    await store.append_entry(EntryDraft("future_state", data={"keep": "opaque"}))
    await store.aclose()
    restored = open_store(tmp_path)
    try:
        snapshot = await restored.snapshot()
        assert snapshot.entries[-1].type == "future_state"
        assert len(ContextProjector().project(snapshot).messages) == 1
    finally:
        await restored.aclose()


async def test_conflicting_duplicate_and_mid_log_damage_fail_without_mutation(tmp_path):
    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "hello"))
    await store.aclose()
    path = tmp_path / "session/entries.jsonl"
    with path.open("ab") as output:
        output.write(b"not valid JSON\n")
    original = path.read_bytes()
    restored = open_store(tmp_path)
    with pytest.raises(StorageError):
        await restored.snapshot()
    assert path.read_bytes() == original
    await restored.aclose()


async def test_native_draft_v0_upgrade_preserves_original_backup(tmp_path):
    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "hello"))
    await store.aclose()
    path = tmp_path / "session/entries.jsonl"
    lines = path.read_bytes().splitlines(keepends=True)
    header = json.loads(lines[0])
    header["schema_version"] = 0
    path.write_bytes((json.dumps(header) + "\n").encode() + b"".join(lines[1:]))
    (tmp_path / "session/position.json").unlink()
    original = path.read_bytes()
    restored = open_store(tmp_path)
    assert "schema_upgrade_pending" in (await restored.snapshot()).issues
    await restored.append_message(Message("u2", "user", "continued"))
    assert json.loads(path.read_bytes().splitlines()[0])["schema_version"] == 1
    assert next((tmp_path / "session/recovery").glob("*.jsonl")).read_bytes() == original
    await restored.aclose()


async def test_external_history_mutation_is_not_silently_overwritten(tmp_path):
    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "hello"))
    path = tmp_path / "session/entries.jsonl"
    with path.open("ab") as output:
        output.write(b'{"unfinished')
    changed = path.read_bytes()
    with pytest.raises(StorageError) as error:
        await store.append_message(Message("u2", "user", "must not overwrite"))
    assert error.value.code == "session_changed_externally"
    assert path.read_bytes() == changed
    await store.aclose()


async def test_identical_duplicate_is_read_once_but_conflict_is_rejected(tmp_path):
    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "hello"))
    await store.aclose()
    path = tmp_path / "session/entries.jsonl"
    row = path.read_bytes().splitlines(keepends=True)[1]
    with path.open("ab") as output:
        output.write(row)
    restored = open_store(tmp_path)
    snapshot = await restored.snapshot()
    assert len(snapshot.entries) == 1
    assert "duplicate_entry" in snapshot.issues
    await restored.aclose()
    changed = json.loads(row)
    changed["message"]["text"] = "conflicting"
    with path.open("ab") as output:
        output.write((json.dumps(changed) + "\n").encode())
    restored = open_store(tmp_path)
    with pytest.raises(StorageError) as error:
        await restored.snapshot()
    assert error.value.code == "duplicate_entry_conflict"
    await restored.aclose()


async def test_compaction_cannot_split_tool_call_and_result(tmp_path):
    from pycode.kernel.contracts.messages import ToolCall, ToolResult

    store = open_store(tmp_path)
    await store.append_message(Message("u1", "user", "task"))
    call = await store.append_message(
        Message("a1", "assistant", tool_calls=(ToolCall("c1", "Read", {}),))
    )
    result = await store.append_message(
        Message("t1", "tool", tool_result=ToolResult("c1", "Read", "body"))
    )
    with pytest.raises(StorageError) as error:
        await store.append_entry(
            EntryDraft("compaction", data={"summary": "summary", "first_kept_entry_id": result})
        )
    assert error.value.code == "unpaired_compaction_boundary"
    await store.append_entry(
        EntryDraft("compaction", data={"summary": "summary", "first_kept_entry_id": call})
    )
    await store.aclose()
