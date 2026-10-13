"""Identify the owning session and its unsettled JSONL run."""

from pycode.core.session_tree import SessionTree


def run_belongs_to_session(header, entry) -> bool:
    owner = entry.data.get("execution_session_id")
    if owner is not None:
        return owner == header.session_id
    # Native legacy runs belong to their original session. Fork copies only
    # conversation entries and never acquires its parent's execution owner.
    return header.parent_session_id is None


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
