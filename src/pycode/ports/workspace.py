"""Read-only workspace inspection boundary."""

from typing import Protocol

from pycode.core.workspace import WorkspaceStatus


class WorkspaceInspector(Protocol):
    def inspect(self) -> WorkspaceStatus: ...
