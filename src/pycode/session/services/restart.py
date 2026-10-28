"""persistent restart recovery ends lost runs without executing their old calls."""

from dataclasses import replace

from pycode.kernel import EntryDraft, Message, SessionTree, ToolContext, ToolResult
from pycode.session.domain.inputs import pending_input_entries


async def recover_conversation(
    store,
    outputs=None,
    *,
    reason="process_exit",
    outcome="interrupted",
    recover_tool=None,
    started_only=False,
    finish_runs=True,
):
    snapshot = await store.snapshot()
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    paired = set()
    ended = set()
    starts = []
    expired_inputs = set()
    for entry in snapshot.entries:
        if entry.data.get("kind") == "run_end":
            ended.add(entry.run_id)
        if entry.type == "input_expired":
            expired_inputs.add(entry.data["input_entry_id"])
    started_operations = {
        entry.operation_id for entry in path if entry.data.get("kind") == "tool_started"
    }
    issued = {}
    for entry in path:
        if entry.message is not None and entry.message.role == "assistant":
            run_id = entry.run_id or entry.message.provider_metadata.get("pycode_run_id", "")
            for call in entry.message.tool_calls:
                issued[(run_id, call.id)] = entry.message.id
        if entry.message is not None and entry.message.tool_result is not None:
            result = entry.message.tool_result
            run_id = entry.run_id or result.run_id or ""
            owner = result.details.get("source_assistant_id") or issued.get(
                (run_id, result.call_id)
            )
            paired.add((owner, result.call_id))
        if entry.data.get("kind") == "run_start":
            starts.append(entry)
    for entry in path:
        message = entry.message
        if message is None or message.role != "assistant":
            continue
        for call in message.tool_calls:
            run_id = entry.run_id or message.provider_metadata.get("pycode_run_id", "")
            key = (message.id, call.id)
            if key in paired:
                continue
            operation_id = f"{run_id}:{message.id}:{call.id}"
            if started_only and operation_id not in started_operations:
                continue
            context = ToolContext(
                snapshot.header.session_id,
                run_id,
                operation_id,
                snapshot.header.workspace,
                call.id,
                call.name,
            )
            confirmed = await recover_tool(context, call, snapshot) if recover_tool else None
            output_id = None
            if confirmed is not None and outputs is not None:
                confirmed = await outputs.prepare(context, confirmed, "head")
                output_id = confirmed.output_id
            elif outputs is not None:
                output_id = await outputs.interrupted_reference(context)
            result = ToolResult(
                call.id,
                call.name,
                "tool_interrupted: the previous process ended without a persistent result. "
                "Effects may have occurred; inspect current state before a new call.",
                is_error=True,
                error_code="tool_interrupted",
                session_id=snapshot.header.session_id,
                run_id=run_id,
                operation_id=operation_id,
                output_id=output_id,
                details={"source_assistant_id": message.id, "output_recovered": bool(output_id)},
            )
            if confirmed is not None:
                result = replace(
                    confirmed,
                    details={
                        **confirmed.details,
                        "source_assistant_id": message.id,
                    },
                )
            result_entry_id = await store.append_message(
                Message(f"interrupted:{message.id}:{call.id}", "tool", tool_result=result)
            )
            if output_id is not None:
                assert outputs is not None
                await outputs.bind(output_id, result_entry_id)
            paired.add(key)
    if not finish_runs:
        return
    for start in starts:
        if (
            start.run_id in ended
            or start.data.get("execution_session_id", snapshot.header.session_id)
            != snapshot.header.session_id
        ):
            continue
        current = await store.snapshot()
        for queued in pending_input_entries(current, start.run_id):
            if queued.id in expired_inputs:
                continue
            await store.append_entry(
                EntryDraft(
                    "input_expired",
                    data={"input_entry_id": queued.id, "queue_kind": queued.data["queue_kind"]},
                    run_id=start.run_id,
                )
            )
        await store.append_entry(
            EntryDraft(
                "custom",
                run_id=start.run_id,
                data={
                    "kind": "run_end",
                    "input_id": start.data["input_id"],
                    "outcome": outcome,
                    "reason": reason,
                    "final_entry_id": None,
                },
            )
        )
    return await store.snapshot()
