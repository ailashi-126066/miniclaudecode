"""Shared application diagnostics for command and interactive interfaces."""

from pycode.session.domain.workspace import ApplicationStatus
from pycode.session.ports.workspace import WorkspaceInspector


class ApplicationService:
    def __init__(self, workspace: WorkspaceInspector, version: str) -> None:
        self._workspace = workspace
        self._version = version

    def status(self) -> ApplicationStatus:
        return ApplicationStatus(
            name="pycode", version=self._version, workspace=self._workspace.inspect()
        )
