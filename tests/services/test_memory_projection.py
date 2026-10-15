"""Model memory bodies are unique while raw history, tool pairing and versions remain intact."""

import json

from tests.services.test_memory_evidence import setup

from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.session import EntryDraft
from pycode.services.context_projector import ContextProjector

USER = ["memories", "user", "u"]
PROJECT = ["memories", "project", "p"]


def record(namespace=USER, *, version="v1", body="RARE_MEMORY_BODY"):
    return {
        "namespace": namespace,
        "memory_id": "same-id",
        "kind": "SemanticMemory",
        "key": "preference",
        "scope": namespace[1],
        "content": body,
        "updated_at": 1,
        "version": version,
        "evidence": [],
    }


async def search(history, identifier, records):
    await history.append_message(
        Message(
            "a-" + identifier,
            "assistant",
            tool_calls=(ToolCall(identifier, "search_user_memory", {"query": "preference"}),),
        )
    )
    return await history.append_message(
        Message(
            "t-" + identifier,
            "tool",
            tool_result=ToolResult(
                identifier,
                "search_user_memory",
                json.dumps(records),
                output_id="output_" + "0" * 32,
                details={"memory_records": records},
            ),
        )
    )


def model_text(context):
    return "\n".join(
        message.tool_result.text if message.tool_result else message.text
        for message in context.messages
    )


async def test_initial_and_repeated_search_emit_body_once_without_changing_raw_tool_result(
    tmp_path,
):
    history, _, _, _ = await setup(tmp_path)
    try:
        saved = record()
        await history.append_entry(EntryDraft("memory_load", data={"records": [saved]}))
        projector = ContextProjector()
        projector.project(await history.snapshot())
        first = await search(history, "first", [saved])
        await search(history, "second", [saved])
        snapshot = await history.snapshot()
        context = projector.project(snapshot)
        assert model_text(context).count("RARE_MEMORY_BODY") == 1
        results = [message.tool_result for message in context.messages if message.tool_result]
        assert {result.call_id for result in results} == {"first", "second"}
        assert all(result.output_id for result in results)
        assert "RARE_MEMORY_BODY" in snapshot.index[first].message.tool_result.text
        assert all("already_in_context" in result.text for result in results)
        await history.append_message(Message("after", "assistant", "done"))
        assert (
            model_text(projector.project(await history.snapshot())).count("RARE_MEMORY_BODY") == 1
        )
    finally:
        await history.aclose()


async def test_new_search_version_replaces_old_initial_body_and_scope_ids_remain_distinct(tmp_path):
    history, _, _, _ = await setup(tmp_path)
    try:
        await history.append_entry(EntryDraft("memory_load", data={"records": [record()]}))
        newer = record(version="v2", body="NEW_MEMORY_BODY")
        project = record(PROJECT, version="p1", body="PROJECT_MEMORY_BODY")
        await search(history, "new", [newer, project])
        context = ContextProjector().project(await history.snapshot())
        text = model_text(context)
        assert "RARE_MEMORY_BODY" not in text
        assert text.count("NEW_MEMORY_BODY") == text.count("PROJECT_MEMORY_BODY") == 1
        assert not context.checkpoint.get("memories")
    finally:
        await history.aclose()


async def test_delete_removes_pinned_body_but_preserves_formal_success_and_call_pair(tmp_path):
    history, _, _, _ = await setup(tmp_path)
    try:
        await history.append_entry(EntryDraft("memory_load", data={"records": [record()]}))
        await history.append_message(
            Message(
                "a",
                "assistant",
                tool_calls=(
                    ToolCall("delete", "manage_user_memory", {"action": "delete", "id": "same-id"}),
                ),
            )
        )
        await history.append_message(
            Message(
                "t",
                "tool",
                tool_result=ToolResult(
                    "delete",
                    "manage_user_memory",
                    "Saved memory operation: same-id",
                    details={
                        "memory_namespace": USER,
                        "memory_deleted": ["same-id"],
                        "memory_records": [],
                    },
                ),
            )
        )
        snapshot = await history.snapshot()
        context = ContextProjector().project(snapshot)
        assert "RARE_MEMORY_BODY" not in model_text(context)
        assert not context.checkpoint.get("memories")
        result = next(message.tool_result for message in context.messages if message.tool_result)
        assert result.call_id == "delete" and not result.is_error
        assert snapshot.entries[-1].message.tool_result.text == "Saved memory operation: same-id"
    finally:
        await history.aclose()
