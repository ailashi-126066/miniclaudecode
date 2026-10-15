"""Content-addressed file versions and reviewable selective restore plans."""

from dataclasses import dataclass


@dataclass(frozen=True)
class FileVersion:
    object_id: str
    mode: str
    size: int


@dataclass(frozen=True)
class Snapshot:
    tree_id: str
    workspace: str
    files: dict[str, FileVersion]
    skipped: tuple[str, ...] = ()


@dataclass(frozen=True)
class FileChange:
    path: str
    before: FileVersion | None
    after: FileVersion | None
    patch: str = ""
    binary: bool = False


@dataclass(frozen=True)
class RestoreFile:
    path: str
    target_tree_id: str
    expected: FileVersion | None
    target: FileVersion | None
    patch: str
    binary: bool


@dataclass(frozen=True)
class RestorePlan:
    restore_id: str
    workspace: str
    before_tree_id: str
    files: tuple[RestoreFile, ...]
    conflicts: tuple[str, ...] = ()


@dataclass(frozen=True)
class RestoreRecord:
    plan: RestorePlan
    status: str
    completed_paths: tuple[str, ...] = ()
    error_code: str | None = None
    compensation_id: str | None = None
