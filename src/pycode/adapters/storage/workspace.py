"""Read workspace capabilities without creating or modifying project state."""

from pathlib import Path

from pycode.core.workspace import WorkspaceStatus


class FileWorkspaceInspector:
    def __init__(self, workspace: Path) -> None:
        self._workspace = workspace

    def inspect(self) -> WorkspaceStatus:
        exists = self._workspace.is_dir()
        is_git = False
        if exists:
            for directory in (self._workspace, *self._workspace.parents):
                git_marker = directory / ".git"
                if git_marker.is_dir() or git_marker.is_file():
                    is_git = True
                    break
        return WorkspaceStatus(
            path=str(self._workspace),
            exists=exists,
            is_git=is_git,
            state_path=str(self._workspace / ".pycode"),
            snapshot_available=is_git,
        )
