# Source: https://github.com/anomalyco/opencode/blob/055d95bb7e278c94baf06235a52cac79dd13ba67/packages/core/src/session/revert.ts
# Adaptation: native branches, first-before per path, last-touch checks and explicit modes.
"""Conversation navigation and selected file restoration share a reviewable plan."""

from dataclasses import replace
from uuid import uuid4

from pycode.core.errors import SnapshotError, StorageError
from pycode.core.revert_state import (
    belongs_to_session,
    decode_plan,
    entry_transaction_id,
    legacy_completion,
    pending_revert,
    transaction_id,
)
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.snapshots import RevertPlan
from pycode.core.values import plain_data
from pycode.ports.history import SessionStore
from pycode.ports.snapshots import SnapshotStore


class RevertService:
    def __init__(self, history: SessionStore, snapshots: SnapshotStore):
        self._history = history
        self._snapshots = snapshots

    async def preview(self, target_leaf_id, mode="dialogue"):
        if mode not in {"dialogue", "code", "both"}:
            raise StorageError("invalid_revert_mode", "Revert mode must be dialogue, code or both")
        snapshot = await self._history.snapshot()
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        positions = {entry.id: index for index, entry in enumerate(path)}
        if target_leaf_id is not None and target_leaf_id not in positions:
            raise StorageError(
                "revert_branch_conflict", "Revert target must be on the current branch"
            )
        boundary = positions.get(target_leaf_id, -1)
        targets, expected = {}, {}
        if mode in {"code", "both"}:
            for entry in path:
                if entry.type != "custom":
                    continue
                if entry.data.get("kind") == "revert_completed":
                    started, _ = self.records(snapshot, entry_transaction_id(entry))
                    if started is not None and started.data.get("plan") is not None:
                        prior = decode_plan(started.data["plan"])
                        if prior.files is not None:
                            for item in prior.files.files:
                                if item.path in expected:
                                    expected[item.path] = (
                                        prior.files.before_tree_id
                                        if entry.data.get("compensated", False)
                                        else item.target_tree_id
                                    )
                    continue
                if entry.data.get("kind") != "file_snapshot":
                    continue
                message_id = entry.data["message_entry_id"]
                if positions.get(message_id, -1) <= boundary:
                    continue
                if entry.data.get("after") is None:
                    raise SnapshotError(
                        "snapshot_unverified", "An operation's after-state was not confirmed"
                    )
                for name in entry.data["paths"]:
                    targets.setdefault(name, entry.data["before"])
                    expected[name] = entry.data["after"]
            if await self._snapshots.capture() is None:
                raise SnapshotError("snapshot_unavailable", "File snapshots require a Git project")
        files = await self._snapshots.preview(targets, expected_trees=expected) if targets else None
        return RevertPlan(
            snapshot.header.session_id,
            snapshot.leaf_id,
            target_leaf_id,
            mode,
            files,
            files.restore_id if files is not None else uuid4().hex,
        )

    async def apply(self, plan: RevertPlan):
        snapshot = await self._history.snapshot()
        identifier = transaction_id(plan) or uuid4().hex
        plan = replace(plan, transaction_id=identifier)
        started, completed = self.records(snapshot, identifier)
        if started is not None:
            saved = started.data.get("plan")
            if saved is None or decode_plan(saved) != plan:
                raise StorageError("revert_plan_changed", "Revert identity has different content")
            if completed is not None:
                return await self.file_record(plan, completed)
            raise StorageError("revert_recovery_required", "Recover the pending revert")
        if pending_revert(snapshot) is not None:
            raise StorageError("revert_recovery_required", "Recover the pending revert")
        if (
            snapshot.header.session_id != plan.session_id
            or snapshot.leaf_id != plan.current_leaf_id
        ):
            raise StorageError("revert_plan_stale", "Conversation changed since revert preview")
        if plan.files is not None and plan.files.conflicts:
            raise SnapshotError(
                "restore_conflict", "External file changes conflict with this revert"
            )
        if plan.mode not in {"dialogue", "code", "both"}:
            raise StorageError("invalid_revert_mode", "Invalid revert mode")
        if plan.target_leaf_id is not None:
            path = SessionTree(snapshot.index).path(snapshot.leaf_id)
            if plan.target_leaf_id not in {entry.id for entry in path}:
                raise StorageError("revert_branch_conflict", "Revert target is not on this branch")
        await self._history.append_entry(
            EntryDraft(
                "custom",
                data={
                    "kind": "revert_started",
                    "session_id": plan.session_id,
                    "transaction_id": identifier,
                    "plan": plain_data(plan),
                    "target_leaf_id": plan.target_leaf_id,
                    "mode": plan.mode,
                    "restore_id": plan.files.restore_id if plan.files is not None else None,
                    "before_tree_id": plan.files.before_tree_id if plan.files is not None else None,
                },
            )
        )
        record = await self._snapshots.restore(plan.files) if plan.files is not None else None
        await self.finish(plan)
        return record

    def records(self, snapshot, identifier):
        started, completed = None, None
        for entry in snapshot.entries:
            if entry.type != "custom" or not belongs_to_session(entry, snapshot.header):
                continue
            if entry_transaction_id(entry) != identifier:
                continue
            if entry.data.get("kind") == "revert_started":
                started = entry
            elif entry.data.get("kind") == "revert_completed":
                completed = entry
        if started is not None and completed is None:
            completed = legacy_completion(snapshot, started)
        return started, completed

    async def file_record(self, plan, completed):
        if plan.files is None:
            return None
        return await self._snapshots.recover(
            plan.files.restore_id,
            compensate=completed.data.get("compensated", False),
            plan=plan.files,
        )

    async def finish(self, plan, *, compensate=False):
        identifier = transaction_id(plan)
        snapshot = await self._history.snapshot()
        active = {entry.id for entry in SessionTree(snapshot.index).path(snapshot.leaf_id)}
        reminder = next(
            (
                entry
                for entry in snapshot.entries
                if entry.type == "custom_message"
                and entry.data.get("transaction_id") == identifier
                and entry.data.get("session_id") == plan.session_id
                and entry.data.get("compensated", False) == compensate
                and entry.id in active
            ),
            None,
        )
        if (compensate or plan.mode in {"dialogue", "both"}) and reminder is None:
            await self._history.branch(plan.current_leaf_id if compensate else plan.target_leaf_id)
        if plan.mode in {"code", "both"} and reminder is None:
            text = (
                "Code restore compensated. Verify current contents before editing."
                if compensate
                else "Code files restored. Verify current contents before editing."
            )
            await self._history.append_entry(
                EntryDraft(
                    "custom_message",
                    data={
                        "text": text,
                        "transaction_id": identifier,
                        "session_id": plan.session_id,
                        "compensated": compensate,
                    },
                )
            )
        await self._history.append_entry(
            EntryDraft(
                "custom",
                data={
                    "kind": "revert_completed",
                    "transaction_id": identifier,
                    "session_id": plan.session_id,
                    "target_leaf_id": plan.target_leaf_id,
                    "mode": plan.mode,
                    "restore_id": plan.files.restore_id if plan.files else None,
                    "compensated": compensate,
                },
            )
        )

    async def recover(self, restore_id, *, compensate=False):
        snapshot = await self._history.snapshot()
        started, completed = self.records(snapshot, restore_id)
        if started is None:
            raise StorageError(
                "revert_transaction_missing", "This session has no matching revert transaction"
            )
        saved = started.data.get("plan")
        plan = (
            decode_plan(saved)
            if saved is not None
            else RevertPlan(
                snapshot.header.session_id,
                started.parent_id,
                started.data["target_leaf_id"],
                started.data["mode"],
                transaction_id=restore_id,
            )
        )
        if completed is not None:
            if completed.data.get("compensated", False) != compensate:
                raise StorageError("revert_already_completed", "Revert already settled differently")
            if saved is None and started.data.get("restore_id") is not None:
                return await self._snapshots.recover(restore_id, compensate=compensate)
            return await self.file_record(plan, completed)
        pending = pending_revert(snapshot)
        if pending is not None and pending.id != started.id:
            raise StorageError("revert_recovery_required", "Another revert needs recovery")
        decision = next(
            (
                entry
                for entry in snapshot.entries
                if entry.type == "custom"
                and entry.data.get("kind") == "revert_recovery"
                and entry_transaction_id(entry) == restore_id
                and belongs_to_session(entry, snapshot.header)
            ),
            None,
        )
        if decision is not None and decision.data["compensated"] != compensate:
            raise StorageError(
                "revert_recovery_conflict", "Continue the previously selected recovery action"
            )
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        if started in path:
            later = path[path.index(started) + 1 :]
        elif plan.mode in {"dialogue", "both"} or (decision is not None and compensate):
            positions = {entry.id: index for index, entry in enumerate(path)}
            boundary = (
                plan.current_leaf_id
                if decision is not None and compensate and plan.current_leaf_id in positions
                else plan.target_leaf_id
            )
            if boundary is not None and boundary not in positions:
                raise StorageError("revert_transaction_stale", "Revert branch changed")
            later = path[positions.get(boundary, -1) + 1 :]
        else:
            raise StorageError("revert_transaction_stale", "Revert branch changed")
        if any(entry.data.get("transaction_id") != restore_id for entry in later):
            raise StorageError(
                "revert_transaction_stale", "Conversation continued after the interrupted revert"
            )
        if decision is None:
            await self._history.append_entry(
                EntryDraft(
                    "custom",
                    data={
                        "kind": "revert_recovery",
                        "transaction_id": restore_id,
                        "session_id": plan.session_id,
                        "compensated": compensate,
                    },
                )
            )
        record = None
        if plan.files is not None or (saved is None and started.data.get("restore_id") is not None):
            record = await self._snapshots.recover(
                restore_id,
                compensate=compensate,
                plan=plan.files,
            )
        await self.finish(plan, compensate=compensate)
        return record
