"""JSONL is the only durable tool-fact journal; no independent operation table."""

from pycode.kernel.domain.history import SessionHeader
from pycode.kernel.domain.messages import Message, ToolCall, ToolResult
from pycode.session.adapters.storage.session import SqliteSessionStore
from pycode.session.services.restart import recover_conversation
from tests.support.requests import TestTiming


def store_at(tmp_path):
    return SqliteSessionStore(
        tmp_path / "s1", SessionHeader("s1", str(tmp_path), "t"), TestTiming()
    )


async def test_complete_model_call_is_durable_and_restart_pairs_it_without_execution(tmp_path):
    store = store_at(tmp_path)
    await store.append_message(Message("u1", "user", "task"))
    await store.append_message(
        Message(
            "a1",
            "assistant",
            tool_calls=(
                ToolCall("c1", "write_file", {"file_path": "/workspace/new.txt", "content": "new"}),
            ),
        )
    )
    await store.aclose()
    reopened = store_at(tmp_path)
    try:
        await reopened.claim_writer()
        snapshot = await recover_conversation(reopened)
        result = snapshot.entries[-1].message.tool_result
        assert result.call_id == "c1" and result.error_code == "tool_interrupted"
        assert not (tmp_path / "new.txt").exists()
    finally:
        await reopened.aclose()


async def test_completed_jsonl_result_is_preserved_without_second_result_path(tmp_path):
    store = store_at(tmp_path)
    await store.append_message(Message("u1", "user", "task"))
    await store.append_message(Message("a1", "assistant", tool_calls=(ToolCall("c1", "Read", {}),)))
    result = ToolResult("c1", "Read", "original result")
    await store.append_message(Message("t1", "tool", tool_result=result))
    await store.aclose()
    original_bytes = (tmp_path / "s1" / "session.sqlite").read_bytes()
    reopened = store_at(tmp_path)
    try:
        await reopened.claim_writer()
        snapshot = await recover_conversation(reopened)
        assert snapshot.entries[-1].message.tool_result == result
        assert (tmp_path / "s1" / "session.sqlite").read_bytes() == original_bytes
        assert not list(tmp_path.rglob("*.jsonl"))
    finally:
        await reopened.aclose()


async def test_missing_result_recovery_is_idempotent_and_branch_scoped(tmp_path):
    store = store_at(tmp_path)
    try:
        root = await store.append_message(Message("u1", "user", "task"))
        await store.append_message(
            Message("a1", "assistant", tool_calls=(ToolCall("abandoned", "Write", {}),))
        )
        await store.branch(root)
        await store.append_message(
            Message("a2", "assistant", tool_calls=(ToolCall("current", "Write", {}),))
        )
        first = await recover_conversation(store)
        second = await recover_conversation(store)
        assert first.entries == second.entries
        results = [
            entry.message.tool_result
            for entry in second.entries
            if entry.message and entry.message.tool_result
        ]
        assert [result.call_id for result in results] == ["current"]
        assert results[0].error_code == "tool_interrupted"
    finally:
        await store.aclose()
