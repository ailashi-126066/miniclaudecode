"""A replaceable filesystem snapshot and selective restore boundary."""

from typing import Protocol

from pycode.snapshot.domain.snapshots import FileChange, RestorePlan, RestoreRecord, Snapshot


class SnapshotStore(Protocol):
    async def capture(self) -> Snapshot | None: ...

    async def get(self, tree_id: str) -> Snapshot: ...

    async def diff(self, before: str, after: str) -> tuple[FileChange, ...]: ...

    async def preview(
        self,
        targets: dict[str, str],
        *,
        expected_tree_id: str | None = None,
        expected_trees: dict[str, str] | None = None,
    ) -> RestorePlan: ...

    async def restore(self, plan: RestorePlan) -> RestoreRecord: ...

    async def recover(
        self, restore_id: str, *, compensate: bool = False, plan: RestorePlan | None = None
    ) -> RestoreRecord: ...
