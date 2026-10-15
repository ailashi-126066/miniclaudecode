"""Canonical fork/compaction retention of immutable history facts."""

from pycode.kernel import SessionEntry


def retained_context_entries(path: tuple[SessionEntry, ...]) -> tuple[SessionEntry, ...]:
    for index in range(len(path) - 1, -1, -1):
        entry = path[index]
        if entry.type == "fork_context":
            allowed = set(entry.data["source_entry_ids"])
            selected = []
            for previous in path[:index]:
                if previous.id in allowed:
                    selected.append(previous)
            selected.extend(path[index:])
            path = tuple(selected)
            break
    latest = None
    for index, entry in enumerate(path):
        if entry.type == "compaction":
            latest = index
    if latest is None:
        return path
    compaction = path[latest]
    kept_id = compaction.data["first_kept_entry_id"]
    selected = [compaction]
    keeping = False
    for entry in path[:latest]:
        if entry.id == kept_id:
            keeping = True
        if keeping and not (entry.type == "message" and entry.message.role == "system"):
            selected.append(entry)
    selected.extend(path[latest + 1 :])
    return tuple(selected)
