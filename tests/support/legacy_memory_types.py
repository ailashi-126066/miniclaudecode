"""Legacy shapes used only to reproduce historical migration inputs."""

from dataclasses import dataclass, field
from typing import Literal


@dataclass(frozen=True)
class MemoryRecord:
    memory_id: str
    key: str
    category: str
    scope: Literal["user", "project"]
    content: str
    created_at: float
    updated_at: float
    evidence: tuple[dict, ...] = ()
    sha256: str = ""


@dataclass(frozen=True)
class MemoryCandidate:
    candidate_id: str
    action: Literal["add", "update", "remove"]
    key: str
    category: str
    scope: str
    content: str
    evidence: tuple[dict, ...]
    source_session_id: str
    prefix_hash: str
    model: str
    created_at: float
    target_id: str | None = None
    durable: bool = True
    status: str = "pending"
    reason: str | None = None
    metadata: dict = field(default_factory=dict)
