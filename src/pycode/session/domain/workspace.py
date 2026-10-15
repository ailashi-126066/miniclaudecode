"""Workspace diagnostics independent of filesystem implementations."""

from dataclasses import dataclass


@dataclass(frozen=True)
class WorkspaceStatus:
    path: str
    exists: bool
    is_git: bool
    state_path: str
    snapshot_available: bool


@dataclass(frozen=True)
class ApplicationStatus:
    name: str
    version: str
    workspace: WorkspaceStatus
