# Snapshot

`module.SnapshotModule` receives a workspace, snapshot directory and excluded roots. It owns selection
of the Git-backed file snapshot implementation. It does not select session branches, restore model
context or orchestrate session reverts.

```python
from pathlib import Path
from pycode.snapshot.module import SnapshotModule

snapshots = SnapshotModule(Path.cwd(), Path.cwd() / ".pycode" / "snapshots")
store = snapshots.store()
```

`factory(workspace, directory, excluded_roots=...)` is also the capture/read-blob factory consumed by
worktree management. The application passes that factory explicitly. File leases and subprocesses
are acquired and released by each snapshot operation; the module has no persistent close operation.
