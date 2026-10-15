"""Mark true references before any cleanup; unknown and live objects stay protected."""

from pycode.kernel import EntryDraft
from pycode.session.domain.storage import RETENTION_SECONDS, mark_objects
from pycode.session.ports.storage import StorageObjects


class StorageGC:
    def __init__(self, objects: StorageObjects, sessions, timing, *, active_references=None):
        self.objects = objects
        self.sessions = sessions
        self.timing = timing
        self.active_references = active_references

    async def status(self):
        inventory = await self.objects.inventory()
        counts = {}
        bytes_by_type = {}
        for item in inventory.objects:
            counts[item.kind] = counts.get(item.kind, 0) + 1
            bytes_by_type[item.kind] = bytes_by_type.get(item.kind, 0) + item.size
        return {
            "objects": counts,
            "bytes": bytes_by_type,
            "quarantined": sum(item.quarantined_at is not None for item in inventory.objects),
        }

    async def collect(self, *, dry_run=False):
        inventory = await self.objects.inventory()
        active = self.active_references() if self.active_references is not None else ()
        marked = mark_objects(inventory, active)
        now = self.timing.now()
        decisions = []
        for item in inventory.objects:
            if item.protected:
                action = "keep_protected"
            elif item.object_id in marked:
                if item.kind == "session" and item.quarantined_at is not None:
                    action = "keep_referenced_quarantine"
                else:
                    action = "restore" if item.quarantined_at is not None else "keep_referenced"
            elif item.quarantined_at is None:
                action = "quarantine"
            elif now - item.quarantined_at >= RETENTION_SECONDS:
                action = "purge"
            else:
                action = "retain_quarantine"
            record = {
                "object_id": item.object_id,
                "type": item.kind,
                "bytes": item.size,
                "last_reference": item.metadata.get("last_reference"),
                "referenced_at_collection": item.object_id in marked,
                "quarantined_at": item.quarantined_at,
                "action": action,
            }
            decisions.append(record)
            if dry_run or action not in {"restore", "quarantine", "purge"}:
                continue
            try:
                await self.objects.apply(item, action, now)
            except Exception as error:
                await self.objects.audit(
                    {
                        **record,
                        "timestamp": now,
                        "result": "failed",
                        "error_type": type(error).__name__,
                    }
                )
                raise
            await self.objects.audit({**record, "timestamp": now, "result": "completed"})
        return {"dry_run": dry_run, "timestamp": now, "decisions": decisions}

    async def delete_session(self, session_id):
        store = await self.sessions.open(session_id)
        try:
            await store.append_entry(
                EntryDraft("session_deleted", data={"deleted_at": self.timing.now()})
            )
            await self.objects.mark_session_deleted(session_id, self.timing.now())
        finally:
            await store.aclose()
        await self.objects.quarantine_session(session_id, self.timing.now())

    async def restore_session(self, session_id):
        await self.objects.restore_session(session_id, self.timing.now())
        store = await self.sessions.open(session_id)
        try:
            await store.append_entry(
                EntryDraft("session_restored", data={"restored_at": self.timing.now()})
            )
        finally:
            await store.aclose()
        # Restoring its roots also restores each still-present quarantined object.
        return await self.collect()
