"""Abrupt restore probe through a real adapter; production has no failure-injection hooks."""

import asyncio
import os
import sys
from pathlib import Path

from pydantic import TypeAdapter

from pycode.snapshot.adapters.git_tree import GitSnapshotStore
from pycode.snapshot.domain.snapshots import RestorePlan, RestoreRecord


class CrashStore(GitSnapshotStore):
    def write_json(self, path, value, *, immutable=False):
        super().write_json(path, value, immutable=immutable)
        if (
            isinstance(value, RestoreRecord)
            and value.status == "restoring"
            and len(value.completed_paths) == 1
        ):
            os._exit(75)


async def main():
    workspace, storage, plan_path = sys.argv[1:4]
    encoded = await asyncio.to_thread(Path(plan_path).read_bytes)
    plan = TypeAdapter(RestorePlan).validate_json(encoded, strict=True)
    store = CrashStore(Path(workspace), Path(storage))
    if sys.argv[4:] == ["compensate"]:
        await store.recover(plan.restore_id, compensate=True)
    else:
        await store.restore(plan)
    raise AssertionError("Expected restore crash was not reached")


if __name__ == "__main__":
    asyncio.run(main())
