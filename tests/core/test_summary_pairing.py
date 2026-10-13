from tests.support.requests import TestTiming

from pycode.adapters.storage.session import JsonlSessionStore
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.models import ModelSelection
from pycode.core.session import SessionHeader
from pycode.core.session_boundaries import valid_kept_boundary
from pycode.core.session_tree import SessionTree
from pycode.core.summary_source import capture_source
from pycode.services.context_projector import ContextProjector


async def test_cut_moves_before_a_call_whose_result_follows_an_intervening_reminder(tmp_path):
    store = JsonlSessionStore(tmp_path / "s1", SessionHeader("s1", "/project", "t"), TestTiming())
    try:
        await store.append_message(Message("u1", "user", "old history " * 1000))
        await store.append_message(Message("a1", "assistant", "old answer " * 1000))
        call = await store.append_message(
            Message("call", "assistant", tool_calls=(ToolCall("c1", "Read", {}),))
        )
        await store.append_message(Message("reminder", "custom", "reminder"))
        await store.append_message(
            Message("t1", "tool", tool_result=ToolResult("c1", "Read", "x" * 8000))
        )
        await store.append_message(Message("a2", "assistant", "recent answer"))
        snapshot = await store.snapshot()
        source = capture_source(
            snapshot,
            ContextProjector().project(snapshot),
            job_id="a" * 32,
            selection=ModelSelection("test", "model", "openai"),
            config_hash="config",
            checkpoint={},
            keep_recent_tokens=2005,
        )
        assert source.first_kept_entry_id == call
        path = SessionTree(snapshot.entries).path(snapshot.leaf_id)
        assert valid_kept_boundary(path, source.first_kept_entry_id)
        assert not any(message.id in {"call", "t1"} for message in source.messages)
    finally:
        await store.aclose()
