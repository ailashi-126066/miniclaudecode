"""Identify the owning session and its unsettled JSONL run."""

from pycode.kernel import SessionTree


def run_belongs_to_session(header, entry) -> bool:
    return entry.data["execution_session_id"] == header.session_id


def pending_run_entry(snapshot):
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    ended = {
        entry.run_id
        for entry in snapshot.entries
        if entry.type == "custom" and entry.data.get("kind") == "run_end"
    }
    for entry in reversed(path):
        if (
            entry.type == "custom"
            and entry.data.get("kind") == "run_start"
            and entry.run_id not in ended
            and run_belongs_to_session(snapshot.header, entry)
        ):
            return entry
    return None
