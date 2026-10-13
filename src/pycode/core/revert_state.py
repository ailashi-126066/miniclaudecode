"""Native revert control records remain separate from filesystem progress."""

from pycode.core.snapshots import FileVersion, RestoreFile, RestorePlan, RevertPlan


def transaction_id(plan: RevertPlan) -> str:
    return plan.transaction_id or (plan.files.restore_id if plan.files is not None else "")


def entry_transaction_id(entry) -> str:
    return entry.data.get("transaction_id") or entry.data.get("restore_id") or entry.id


def belongs_to_session(entry, header) -> bool:
    owner = entry.data.get("session_id")
    return owner == header.session_id or (owner is None and header.parent_session_id is None)


def legacy_completion(snapshot, started):
    if started.data.get("transaction_id") or started.data.get("restore_id"):
        return None
    position = snapshot.entries.index(started)
    for entry in snapshot.entries[position + 1 :]:
        if entry.type != "custom" or not belongs_to_session(entry, snapshot.header):
            continue
        if entry.data.get("kind") == "revert_started":
            break
        if (
            entry.data.get("kind") == "revert_completed"
            and not entry.data.get("transaction_id")
            and not entry.data.get("restore_id")
            and entry.data.get("mode") == started.data.get("mode")
            and entry.data.get("target_leaf_id") == started.data.get("target_leaf_id")
        ):
            return entry
    return None


def pending_revert(snapshot):
    ended = {
        entry_transaction_id(entry)
        for entry in snapshot.entries
        if entry.type == "custom"
        and entry.data.get("kind") == "revert_completed"
        and belongs_to_session(entry, snapshot.header)
    }
    for entry in reversed(snapshot.entries):
        if entry.type != "custom" or entry.data.get("kind") != "revert_started":
            continue
        if not belongs_to_session(entry, snapshot.header):
            continue
        identifier = entry_transaction_id(entry)
        if identifier not in ended and legacy_completion(snapshot, entry) is None:
            return entry
    return None


def decode_plan(value: dict) -> RevertPlan:
    file_plan = value.get("files")
    files = None
    if file_plan is not None:
        entries = []
        for item in file_plan["files"]:
            expected = FileVersion(**item["expected"]) if item["expected"] is not None else None
            target = FileVersion(**item["target"]) if item["target"] is not None else None
            entries.append(
                RestoreFile(
                    item["path"],
                    item["target_tree_id"],
                    expected,
                    target,
                    item["patch"],
                    item["binary"],
                )
            )
        files = RestorePlan(
            file_plan["restore_id"],
            file_plan["workspace"],
            file_plan["before_tree_id"],
            tuple(entries),
            tuple(file_plan.get("conflicts", ())),
        )
    if value["mode"] not in ("dialogue", "code", "both"):
        raise ValueError("Invalid persisted revert mode")
    return RevertPlan(
        value["session_id"],
        value["current_leaf_id"],
        value["target_leaf_id"],
        value["mode"],
        files,
        value.get("transaction_id", ""),
    )
