"""Trusted memory evidence and job-owned permits; model IDs never create authority."""

import re
from dataclasses import dataclass, field

from pycode.core.errors import StorageError, ToolError
from pycode.core.hashing import data_hash
from pycode.core.memory import SECRET_PATTERNS, content_hash
from pycode.core.session_tree import SessionTree
from pycode.core.values import freeze_value, plain_data


@dataclass(frozen=True)
class MemoryEvidenceText:
    session_id: str
    entry_id: str
    role: str
    text: str
    start: int = 0
    tool_name: str | None = None
    tool_arguments: dict = field(default_factory=dict)
    can_verify_outcome: bool = False

    def __post_init__(self):
        object.__setattr__(self, "tool_arguments", freeze_value(self.tool_arguments))


@dataclass(frozen=True)
class MemoryEvidenceSnapshot:
    session_id: str
    workspace: str
    leaf_id: str | None
    path_ids: tuple[str, ...]
    prefix_hash: str
    texts: tuple[MemoryEvidenceText, ...]
    current_user_text: str = ""
    eligible_ids: tuple[str, ...] = ()

    def check_current(self, current):
        path = SessionTree(current.index).path(current.leaf_id)
        prefix = path[: len(self.path_ids)]
        if (
            current.header.session_id != self.session_id
            or current.header.workspace != self.workspace
            or tuple(e.id for e in prefix) != self.path_ids
            or data_hash([plain_data(e) for e in prefix]) != self.prefix_hash
        ):
            raise StorageError(
                "memory_source_changed", "Memory source is no longer on the same branch"
            )


@dataclass(frozen=True)
class MemoryEvidenceDecision:
    supported: bool
    durable: bool
    scope_valid: bool
    deletion_requested: bool = False
    outcome_verified: bool = False
    reason: str = ""


class MemoryEvidencePolicy:
    def __init__(self, evidence, scopes, *, evaluated_rule_ids=(), evaluated_rules=None):
        if evaluated_rule_ids:
            raise ValueError("Evaluation IDs alone cannot authorize a rule version")
        if not scopes or any(scope not in {"user", "project"} for scope in scopes.values()):
            raise ValueError("Memory evidence requires trusted user/project scope bindings")
        self.evidence = evidence
        self.scopes = freeze_value(scopes)
        self.sources = {(text.session_id, text.entry_id): text for text in evidence.texts}
        self.evaluated_rules = evaluated_rules if evaluated_rules is not None else {}

    def validate(self, namespace, key, value, previous, *, delete_sources=()):

        if namespace not in self.scopes:
            raise ToolError("memory_scope_denied", "Memory scope is outside this task")
        sources = delete_sources if value is None else value["content"]["sources"]
        if not sources:
            raise ToolError("memory_evidence_missing", "Memory changes require source evidence")
        inherited = previous["value"]["content"]["sources"] if previous else []
        checked = []
        for source in sources:
            datum = self.sources.get((source.get("session_id"), source.get("entry_id")))
            if datum is None:
                if value is not None and source in inherited:
                    continue
                raise ToolError(
                    "memory_evidence_invalid", "Memory evidence is outside the trusted source range"
                )
            quote = source.get("quote")
            if (
                datum.role not in {"user", "tool"}
                or not isinstance(quote, str)
                or not quote.strip()
                or len(quote) > 16000
                or quote not in datum.text
            ):
                raise ToolError(
                    "memory_evidence_invalid", "Memory quote is absent from an admissible source"
                )
            if any(re.search(pattern, quote) for pattern in SECRET_PATTERNS):
                raise ToolError("memory_contains_secret", "Memory evidence contains a secret")

            if source.get("sha256") not in {None, content_hash(quote)}:
                raise ToolError("memory_evidence_invalid", "Memory evidence checksum differs")
            checked.append((source, datum))
        if not checked:
            raise ToolError(
                "memory_evidence_missing",
                "Changes require evidence from this task, not only an old record",
            )
        if value is None:
            if previous is None or not any(d.role == "user" for _, d in checked):
                raise ToolError(
                    "memory_delete_unsupported",
                    "Deletion requires an existing target and user evidence",
                )
        elif value["kind"] == "SemanticMemory":
            category = value["content"]["category"]
            if category == "user_preference" and self.scopes[namespace] != "user":
                raise ToolError(
                    "memory_scope_denied", "Global user preferences belong to user memory"
                )
            if category == "project_knowledge" and self.scopes[namespace] != "project":
                raise ToolError(
                    "memory_scope_denied", "Project knowledge belongs to the current project"
                )
            if category in {"user_preference", "correction_feedback"} and not any(
                d.role == "user" for _, d in checked
            ):
                raise ToolError("memory_evidence_invalid", "User preferences require user evidence")
        elif value["kind"] == "ProceduralMemory":
            origin = value["content"]["origin"]
            if origin == "model_evaluated" and self.evaluated_rules.get((namespace, key)) != (
                data_hash(value),
                data_hash(previous),
            ):
                raise ToolError(
                    "memory_rule_unevaluated", "Inferred rules require a completed task comparison"
                )
            if origin == "user_explicit" and not any(d.role == "user" for _, d in checked):
                raise ToolError("memory_evidence_invalid", "Explicit rules require user feedback")
        elif value["content"]["verification"] == "verified" and not any(
            d.can_verify_outcome for _, d in checked
        ):
            raise ToolError(
                "memory_outcome_unverified", "A completed chat is not a verified task outcome"
            )
        return tuple(checked)


class MemoryWritePermit:
    """Issued after review, bound to exact changes and the observed target versions."""

    def __init__(self):
        self.entries = {}

    def issue(self, namespace, changes, previous):
        self.entries = freeze_value(
            {
                (namespace, key): (data_hash(value), data_hash(previous.get(key)))
                for key, value in changes.items()
            }
        )

    def __call__(self, namespace, key, value, previous):
        expected = self.entries.get((namespace, key))
        if expected != (data_hash(value), data_hash(previous)):
            raise ToolError(
                "memory_write_unvalidated",
                "Memory change was not validated against this target version",
            )


@dataclass(frozen=True)
class PreparedMemoryUpdate:
    revisions: dict
    previous: dict
    changes: dict
    group_receipts: dict = field(default_factory=dict)
    retained_proposals: dict = field(default_factory=dict)
    evaluations: dict = field(default_factory=dict)

    def __post_init__(self):
        for name in (
            "revisions",
            "previous",
            "changes",
            "group_receipts",
            "retained_proposals",
            "evaluations",
        ):
            object.__setattr__(self, name, freeze_value(getattr(self, name)))


@dataclass(frozen=True)
class MemoryCommitReport:
    accepted_ids: tuple[str, ...]
    rejected_groups: tuple[dict, ...]
    can_advance: bool
    reorganize_reason: str | None = None

    def __post_init__(self):
        object.__setattr__(self, "rejected_groups", freeze_value(self.rejected_groups))


@dataclass(frozen=True)
class MemoryBehaviorFeedback:
    text: str
    title: str
    summary: str
    applicability: tuple[str, ...]
    sources: tuple[dict, ...]
    decision: MemoryEvidenceDecision
    explicit: bool = True

    def __post_init__(self):
        object.__setattr__(self, "sources", freeze_value(self.sources))
