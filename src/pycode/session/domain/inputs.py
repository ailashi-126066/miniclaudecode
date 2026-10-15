# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/agent.ts
# Adaptation: native queued entries; delivery is acknowledged only by a settled model source.
"""Pure branch-local selection of durable inputs still awaiting a model response."""

from typing import Literal

from pycode.kernel import SessionEntry, SessionSnapshot, SessionTree

InputKind = Literal["steer", "follow_up"]


def pending_input_entries(snapshot: SessionSnapshot, run_id: str) -> tuple[SessionEntry, ...]:
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    positions = {entry.id: index for index, entry in enumerate(path)}
    responded_through = -1
    for entry in path:
        if entry.type != "message":
            continue
        message = entry.require_message()
        if message.role != "assistant":
            continue
        if message.provider_metadata.get("pycode_run_id") != run_id:
            continue
        source_id = message.provider_metadata.get("pycode_source_leaf_id")
        if not isinstance(source_id, str):
            continue
        responded_through = max(responded_through, positions.get(source_id, -1))
    consumed = {
        entry.require_message().id
        for entry in path[: responded_through + 1]
        if entry.type == "message" and entry.require_message().role == "user"
    }
    return tuple(
        entry
        for entry in path
        if entry.type == "input_queued"
        and entry.run_id == run_id
        and entry.require_message().id not in consumed
    )
