"""Declared Python Skill file arguments retain the workspace path boundary."""

from pathlib import Path

from pycode.core.errors import ToolError


def resolve_workspace_path(workspace, value):
    root = Path(workspace).resolve()
    raw = Path(value).expanduser()
    path = (raw if raw.is_absolute() else root / raw).resolve()
    if not path.is_relative_to(root):
        raise ToolError("outside_workspace", "File path is outside the permitted workspace")
    for part in path.relative_to(root).parts:
        if part in {".git", ".pycode"}:
            raise ToolError("outside_workspace", "Internal project storage is not a file argument")
    return path
