"""Durable knowledge candidates require actual source evidence and a narrow category."""

import hashlib
import re
from dataclasses import dataclass, field
from typing import Literal

from pycode.core.errors import ToolError

CATEGORIES = frozenset({"user_preference", "correction_feedback", "project_knowledge"})
SECRET_PATTERNS = (
    r"\bsk-[A-Za-z0-9_-]{16,}\b",
    r"(?i)(api[_ -]?key|password|secret|access[_ -]?token)\s*[:=]\s*\S{6,}",
    r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b",
)


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


def expected_scope(category):
    return "project" if category == "project_knowledge" else "user"


def validate_candidate(candidate, source_entries):
    if candidate.category not in CATEGORIES or candidate.action not in {"add", "update", "remove"}:
        raise ToolError("invalid_memory_category", "Memory category or action is unsupported")
    if not candidate.durable or any(
        term in candidate.content.casefold() for term in ("this task only", "本次任务", "当前会话")
    ):
        raise ToolError("temporary_memory", "Task-local facts are not long-term memory")
    if not candidate.key.strip() or not candidate.content.strip() or len(candidate.content) > 16000:
        raise ToolError("invalid_memory_content", "Memory must contain bounded, classified content")
    for pattern in SECRET_PATTERNS:
        if re.search(pattern, candidate.content):
            raise ToolError("memory_contains_secret", "Secrets must not enter memory review")
    if not candidate.evidence:
        raise ToolError("memory_evidence_missing", "Memory requires source evidence")
    for item in candidate.evidence:
        entry = source_entries.get(item.get("entry_id"))
        quote = item.get("quote")
        if (
            entry is None
            or entry.message is None
            or not isinstance(quote, str)
            or not quote.strip()
        ):
            raise ToolError(
                "memory_evidence_invalid", "Evidence must identify a real completed message"
            )
        if entry.message.role == "assistant":
            raise ToolError(
                "memory_evidence_invalid", "An assistant claim alone is not memory evidence"
            )
        text = entry.message.text
        if entry.message.tool_result is not None:
            text = entry.message.tool_result.text
        if quote not in text:
            raise ToolError("memory_evidence_invalid", "Evidence quote is absent from its source")
        for pattern in SECRET_PATTERNS:
            if re.search(pattern, quote):
                raise ToolError(
                    "memory_contains_secret", "Evidence containing secrets must not enter review"
                )
        digest = hashlib.sha256(quote.encode("utf-8")).hexdigest()
        if item.get("sha256", digest) != digest:
            raise ToolError("memory_evidence_invalid", "Evidence checksum does not match its quote")


def content_hash(content):
    return hashlib.sha256(content.encode("utf-8")).hexdigest()
