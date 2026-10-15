"""Own Git snapshot implementation selection without session orchestration."""

from copy import copy
from pathlib import Path

from pycode.snapshot.adapters.git_tree import GitSnapshotStore


class SnapshotModule:
    def scoped(self, workspace, directory):
        scoped = copy(self)
        scoped.workspace, scoped.directory = workspace, directory
        return scoped

    def __init__(
        self, workspace: Path, directory: Path, *, excluded_roots: tuple[Path, ...] = ()
    ) -> None:
        self.workspace = workspace
        self.directory = directory
        self.excluded_roots = excluded_roots

    def store(self) -> GitSnapshotStore:
        return self.factory(self.workspace, self.directory, excluded_roots=self.excluded_roots)

    @staticmethod
    def factory(
        workspace: Path, directory: Path, *, excluded_roots: tuple[Path, ...] = ()
    ) -> GitSnapshotStore:
        """Also supplies the capture/read_blob capability consumed by worktrees."""
        return GitSnapshotStore(workspace, directory, excluded_roots=excluded_roots)
