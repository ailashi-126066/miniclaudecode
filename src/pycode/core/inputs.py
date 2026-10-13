# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/agent.ts
# Adaptation: native queued entries; delivery is acknowledged only by a settled model source.
"""Pure branch-local selection of durable inputs still awaiting a model response."""

from typing import Literal

from pycode.core.session import SessionEntry, SessionSnapshot
from pycode.core.session_tree import SessionTree

InputKind = Literal["steer", "follow_up"]


def pending_input_entries(snapshot: SessionSnapshot, run_id: str) -> tuple[SessionEntry, ...]:
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    positions = {entry.id: index for index, entry in enumerate(path)}
    responded_through = -1
    for entry in path:
        message = entry.message
        if entry.type != "message" or message.role != "assistant":
            continue
        if message.provider_metadata.get("pycode_run_id") != run_id:
            continue
        source_id = message.provider_metadata.get("pycode_source_leaf_id")
        responded_through = max(responded_through, positions.get(source_id, -1))
    consumed = {
        entry.message.id
        for entry in path[: responded_through + 1]
        if entry.type == "message" and entry.message.role == "user"
    }
    return tuple(
        entry
        for entry in path
        if entry.type == "input_queued"
        and entry.run_id == run_id
        and entry.message.id not in consumed
    )
