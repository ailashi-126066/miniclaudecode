"""Scoped JSON memory snapshots and shared provenance utilities."""

import hashlib
from dataclasses import dataclass
from typing import Literal

CATEGORIES = frozenset({"user_preference", "correction_feedback", "project_knowledge"})
SECRET_PATTERNS = (
    r"\bsk-[A-Za-z0-9_-]{16,}\b",
    r"(?i)(api[_ -]?key|password|secret|access[_ -]?token)\s*[:=]\s*\S{6,}",
    r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b",
)


@dataclass(frozen=True)
class MemorySnapshotRecord:
    """One version of a scoped JSON memory projected into a user task."""

    namespace: tuple[str, ...]
    memory_id: str
    kind: str
    key: str
    scope: Literal["user", "project"]
    content: str
    created_at: float
    updated_at: float
    version: str
    evidence: tuple[dict, ...]
    applicability: tuple[str, ...] = ()
    verification: str | None = None


def content_hash(content):
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def snapshot_record(namespace, key, record, scope):
    from datetime import datetime

    from pycode.core.hashing import data_hash

    value = record["value"]
    content = value["content"]
    created = datetime.fromisoformat(record["created_at"])
    updated = datetime.fromisoformat(record["updated_at"])
    return MemorySnapshotRecord(
        namespace,
        key,
        value["kind"],
        content["title"],
        scope,
        content["body"],
        created.timestamp(),
        updated.timestamp(),
        data_hash({"value": value, "updated_at": updated.isoformat()}),
        tuple(content["sources"]),
        tuple(content.get("applicability", ())),
        content.get("verification"),
    )
