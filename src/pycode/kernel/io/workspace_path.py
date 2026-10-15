"""Declared Python Skill file arguments retain the workspace path boundary."""

from pathlib import Path

from pycode.kernel.errors import ToolError
from pycode.kernel.path_policy import is_internal_component


def resolve_workspace_path(workspace, value):
    root = Path(workspace).resolve()
    raw = Path(value).expanduser()
    path = (raw if raw.is_absolute() else root / raw).resolve()
    if not path.is_relative_to(root):
        raise ToolError("outside_workspace", "File path is outside the permitted workspace")
    for part in path.relative_to(root).parts:
        if is_internal_component(part):
            raise ToolError("outside_workspace", "Internal project storage is not a file argument")
    return path
