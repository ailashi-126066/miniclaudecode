"""Native revert control records remain separate from filesystem progress."""

from pycode.session.domain.revert import RevertPlan
from pycode.snapshot import FileVersion, RestoreFile, RestorePlan


def transaction_id(plan: RevertPlan) -> str:
    return plan.transaction_id or (plan.files.restore_id if plan.files is not None else "")


def entry_transaction_id(entry) -> str:
    return entry.data.get("transaction_id") or entry.id


def belongs_to_session(entry, header) -> bool:
    owner = entry.data.get("session_id")
    return owner == header.session_id or (owner is None and header.parent_session_id is None)


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
        if identifier not in ended:
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
