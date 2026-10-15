# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/agent.ts
# Adaptation: persist before acceptance; native branch provenance replaces process-only queues.
"""Durable queue admission and rebuilding, independent of Graph state."""

import asyncio

from pycode.kernel import EntryDraft, Message, SessionTree, StorageError
from pycode.session.domain.inputs import InputKind, pending_input_entries
from pycode.session.ports.history import SessionStore
from pycode.session.services.input_queue import PendingInputs


class DurableInputs:
    def __init__(self, store: SessionStore):
        self._store = store
        self._admission = asyncio.Lock()
        self._closed_runs = set()

    async def submit(self, message: Message, kind: InputKind, run_id: str) -> bool:
        async with self._admission:
            return await self.submit_locked(message, kind, run_id)

    async def submit_locked(self, message: Message, kind: InputKind, run_id: str) -> bool:
        if message.role != "user" or not message.id or kind not in ("steer", "follow_up"):
            raise StorageError(
                "invalid_queued_input", "Queued input requires user ID and queue kind"
            )
        snapshot = await self._store.snapshot()
        active = {entry.id for entry in SessionTree(snapshot.index).path(snapshot.leaf_id)}
        for entry in snapshot.entries:
            if (
                entry.type not in {"message", "input_queued"}
                or entry.require_message().id != message.id
            ):
                continue
            if entry.message != message or (
                entry.type == "input_queued"
                and (entry.data["queue_kind"] != kind or entry.run_id != run_id)
            ):
                raise StorageError("input_id_conflict", "Input ID has different content or owner")
            if entry.id not in active:
                raise StorageError("input_branch_conflict", "Input ID belongs to another branch")
            return False
        if run_id in self._closed_runs:
            raise StorageError("input_admission_closed", "Task is ending; submit a new task")
        await self._store.append_entry(
            EntryDraft(
                "input_queued",
                message=message,
                data={"queue_kind": kind},
                run_id=run_id,
            )
        )
        return True

    async def pending(self, run_id: str) -> PendingInputs:
        queue = PendingInputs()
        for entry in pending_input_entries(await self._store.snapshot(), run_id):
            queue.submit(entry.require_message(), entry.data["queue_kind"])
        return queue

    async def finish_boundary(self, run_id):
        async with self._admission:
            pending = await self.pending(run_id)
            if not pending.has("steer") and not pending.has("follow_up"):
                self._closed_runs.add(run_id)
            return pending
