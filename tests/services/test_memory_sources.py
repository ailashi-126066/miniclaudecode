"""Source windows preserve every character and message in forward order across resumes."""

import pytest
from tests.services.test_memory_evidence import setup

from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.core.errors import StorageError
from pycode.core.hashing import data_hash
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.session_tree import SessionTree
from pycode.core.tools import ToolContext
from pycode.core.values import plain_data
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.services.memory_sources import MemorySourcePlanner


def receipt(snapshot, window):
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    return {
        "session_id": snapshot.header.session_id,
        "branch_id": snapshot.leaf_id,
        "prefix_hash": data_hash([plain_data(entry) for entry in path]),
        "entry_ids": list(window.entry_ids),
        "start": {"entry_id": window.entry_ids[0], "offset": window.ranges[window.entry_ids[0]][0]},
        "end": {"entry_id": window.entry_ids[-1], "offset": window.ranges[window.entry_ids[-1]][1]},
        "changes_sha256": data_hash({}),
        "end_output_positions": window.end_output_positions,
    }


@pytest.mark.parametrize(
    "text", ["永远先解释参数来源。" + "上下文材料" * 14000, "first preference\n" + "x" * 65000]
)
async def test_large_single_source_restarts_from_exact_saved_character_cursor(tmp_path, text):
    history, _, _, entry = await setup(tmp_path, text=text)
    try:
        snapshot = await history.snapshot()
        batches, pieces = {}, []
        for index in range(200):
            planner = MemorySourcePlanner(token_budget=800)
            window = await planner.next(snapshot, batches)
            if window is None:
                break
            start, end = window.ranges[entry]
            assert start == sum(len(piece) for piece in pieces) and end > start
            captured = await capture_memory_evidence(
                snapshot, entry_ids=window.entry_ids, ranges=window.ranges
            )
            pieces.append(captured.texts[0].text)
            batches[str(index)] = receipt(snapshot, window)
        else:
            raise AssertionError("Source planning made no bounded progress")
        assert "".join(pieces) == text
        assert len(batches) > 1
        assert batches["0"]["end"]["offset"] < len(text)
    finally:
        await history.aclose()


async def test_many_messages_keep_first_preference_and_never_skip_middle_sources(tmp_path):
    history, _, _, first = await setup(tmp_path, text="Always explain parameter flow.")
    try:
        expected = {first: "Always explain parameter flow."}
        for index in range(22):
            text = f"message {index}: " + "material " * 300
            entry = await history.append_message(Message(f"m{index}", "user", text))
            expected[entry] = text
        snapshot = await history.snapshot()
        batches, actual = {}, {key: "" for key in expected}
        seen = []
        for index in range(100):
            window = await MemorySourcePlanner(token_budget=1000).next(snapshot, batches)
            if window is None:
                break
            for key in window.entry_ids:
                start, end = window.ranges[key]
                assert start == len(actual[key])
                actual[key] += expected[key][start:end]
                if key not in seen:
                    seen.append(key)
            batches[str(index)] = receipt(snapshot, window)
        assert actual == expected and seen == list(expected)
    finally:
        await history.aclose()


async def test_branch_navigation_does_not_adopt_processed_tail_from_another_branch(tmp_path):
    history, _, _, first = await setup(tmp_path, text="first")
    try:
        second = await history.append_message(Message("second", "user", "old tail"))
        old = await history.snapshot()
        window = await MemorySourcePlanner().next(old, {})
        batches = {"old": receipt(old, window)}
        await history.branch(first)
        replacement = await history.append_message(Message("replacement", "user", "new tail"))
        current = await history.snapshot()
        next_window = await MemorySourcePlanner().next(current, batches)
        assert next_window.entry_ids == (first, replacement)
        assert second not in next_window.entry_ids
    finally:
        await history.aclose()


async def test_tampered_progress_prefix_fails_instead_of_returning_an_empty_window(tmp_path):
    history, _, _, _ = await setup(tmp_path)
    try:
        snapshot = await history.snapshot()
        window = await MemorySourcePlanner().next(snapshot, {})
        batch = receipt(snapshot, window)
        batch["prefix_hash"] = "0" * 64
        with pytest.raises(StorageError) as error:
            await MemorySourcePlanner().next(snapshot, {"bad": batch})
        assert error.value.code == "memory_source_changed"
    finally:
        await history.aclose()


async def test_tool_windows_include_beyond_preview_and_empty_call_message(
    tmp_path,
):
    history, _, _, first = await setup(tmp_path, text="Run a test.")
    try:
        call_entry = await history.append_message(
            Message("a", "assistant", tool_calls=(ToolCall("call", "Bash", {"command": "pytest"}),))
        )
        outputs = FileToolOutputStore(tmp_path / "sessions", "s", history)
        body = "材料\n" * 18000 + "all checks passed\n"
        result = await outputs.prepare(
            ToolContext("s", "r", "op", str(tmp_path), "call", "Bash"),
            ToolResult("call", "Bash", body, exit_code=0),
            "head",
        )
        tool_entry = await history.append_message(Message("t", "tool", tool_result=result))
        await outputs.bind(result.output_id, tool_entry)
        assert "all checks passed" not in result.text
        snapshot = await history.snapshot()
        batches, tool_text, all_ids = {}, "", []
        for index in range(100):
            window = await MemorySourcePlanner(output_reader=outputs, token_budget=1200).next(
                snapshot, batches
            )
            if window is None:
                break
            ranges = {key: value for key, value in window.ranges.items() if value[0] != value[1]}
            captured = await capture_memory_evidence(
                snapshot,
                entry_ids=window.entry_ids,
                ranges=ranges,
                output_reader=outputs,
                output_positions=window.output_positions,
            )
            for source in captured.texts:
                if source.entry_id == tool_entry:
                    assert source.start == len(tool_text)
                    tool_text += source.text
                    assert source.can_verify_outcome
                if source.entry_id not in all_ids:
                    all_ids.append(source.entry_id)
            batches[str(index)] = receipt(snapshot, window)
        assert tool_text == body and all_ids == [first, call_entry, tool_entry]
    finally:
        await history.aclose()


async def test_output_readback_failure_never_uses_preview(tmp_path):
    history, _, _, _ = await setup(tmp_path)
    try:
        await history.append_message(
            Message("a", "assistant", tool_calls=(ToolCall("call", "Bash", {"command": "pytest"}),))
        )
        await history.append_message(
            Message(
                "t",
                "tool",
                tool_result=ToolResult("call", "Bash", "preview", output_id="output_" + "0" * 32),
            )
        )
        with pytest.raises(StorageError) as error:
            await MemorySourcePlanner().next(await history.snapshot(), {})
        assert error.value.code == "memory_source_output_missing"
    finally:
        await history.aclose()
