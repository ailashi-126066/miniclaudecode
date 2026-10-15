"""Persisted memory jobs retain source identity and proposals, never a second active memory."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from pycode.core.memory_evaluation import MemoryRuleEvaluation
from pycode.core.memory_schema import (
    Identity,
    MemoryOutputPosition,
    MemoryTargetVersion,
    MemoryValue,
    SourceCursor,
)
from pycode.core.summary_source import data_hash

REORGANIZE_ERRORS = frozenset(
    {
        "memory_target_conflict",
        "memory_revision_conflict",
        "memory_claim_unsupported",
        "memory_delete_unsupported",
        "memory_outcome_unverified",
        "memory_evidence_invalid",
        "memory_evidence_missing",
        "invalid_memory_content",
        "memory_scope_denied",
        "memory_contains_secret",
        "memory_rule_unevaluated",
        "memory_rule_requires_optimizer",
        "memory_completed_group_protected",
        "memory_retry_source_invalid",
        "memory_feedback_invalid",
        "memory_rule_invalid",
        "memory_prompt_mapping_invalid",
        "memory_behavior_stage_failed",
        "memory_rule_evaluation_target_changed",
    }
)


class MemoryJob(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    schema_version: Literal[1] = 1
    job_id: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    namespace: tuple[Identity, ...]
    workspace: Annotated[str, Field(min_length=1, max_length=4096)]
    session_id: Identity
    run_id: Identity
    source_leaf_id: Identity
    source_prefix_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    entry_ids: Annotated[list[Identity], Field(min_length=1, max_length=64)]
    ranges: dict[Identity, tuple[int, int]]
    start: SourceCursor
    end: SourceCursor
    status: Literal["pending", "running", "proposed", "pending_retry", "completed", "obsolete"] = (
        "pending"
    )
    revision: Annotated[int, Field(ge=0)] = 0
    created_at: Annotated[float, Field(ge=0)]
    updated_at: Annotated[float, Field(ge=0)]
    proposals: Annotated[dict[Identity, MemoryValue | None] | None, Field(max_length=64)] = None
    observed_revision: Annotated[int | None, Field(ge=0)] = None
    observed_targets: dict[Identity, MemoryTargetVersion] = Field(default_factory=dict)
    error_code: Identity | None = None
    reorganize_reason: Identity | None = None
    output_positions: dict[Identity, MemoryOutputPosition] = Field(default_factory=dict)
    end_output_positions: dict[Identity, MemoryOutputPosition] = Field(default_factory=dict)
    evaluations: Annotated[dict[Identity, MemoryRuleEvaluation], Field(max_length=8)] = Field(
        default_factory=dict
    )

    def source_identity(self):
        value = self.model_dump(
            mode="json",
            include={
                "namespace",
                "workspace",
                "session_id",
                "source_leaf_id",
                "source_prefix_hash",
                "entry_ids",
                "ranges",
                "start",
                "end",
            },
        )
        # Empty positions retain the identity of jobs created before byte cursors existed.
        if self.output_positions or self.end_output_positions:
            value.update(
                self.model_dump(mode="json", include={"output_positions", "end_output_positions"})
            )
        return value

    @model_validator(mode="after")
    def validate_source(self):
        if self.reorganize_reason is not None and self.reorganize_reason not in REORGANIZE_ERRORS:
            raise ValueError("Memory reorganization requires a content or target failure")
        if any(
            key != evaluation.evaluation_id
            or evaluation.namespace != self.namespace
            or evaluation.batch_id != self.job_id
            or evaluation.source_prefix_hash != self.source_prefix_hash
            for key, evaluation in self.evaluations.items()
        ):
            raise ValueError("Rule evaluation belongs to another job or source")
        if (
            not self.namespace
            or len(set(self.entry_ids)) != len(self.entry_ids)
            or set(self.ranges) != set(self.entry_ids)
            or self.start.entry_id != self.entry_ids[0]
            or self.end.entry_id != self.entry_ids[-1]
            or self.start.offset != self.ranges[self.entry_ids[0]][0]
            or self.end.offset != self.ranges[self.entry_ids[-1]][1]
            or any(start < 0 or end < start for start, end in self.ranges.values())
            or self.updated_at < self.created_at
        ):
            raise ValueError("Memory job source range or timestamps are invalid")
        if set(self.output_positions) != set(self.end_output_positions) or not set(
            self.output_positions
        ) <= set(self.entry_ids):
            raise ValueError("Output positions must identify paired source ranges")
        for entry_id, beginning in self.output_positions.items():
            ending = self.end_output_positions[entry_id]
            if (
                (beginning.character_offset, ending.character_offset) != self.ranges[entry_id]
                or beginning.output_id != ending.output_id
                or ending.absolute_byte_offset < beginning.absolute_byte_offset
                or ending.line_offset < beginning.line_offset
            ):
                raise ValueError("Output positions differ from the immutable source range")
        if self.job_id != data_hash(self.source_identity()):
            raise ValueError("Memory job identity differs from its immutable source")
        if self.status == "proposed" and (self.proposals is None or self.observed_revision is None):
            raise ValueError("Proposed jobs require staged changes and the observed revision")
        return self


def memory_job(
    namespace,
    snapshot,
    entry_ids,
    ranges,
    run_id,
    now,
    *,
    output_positions=None,
    end_output_positions=None,
):
    from pycode.core.session_tree import SessionTree
    from pycode.core.values import plain_data

    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    ids = list(entry_ids)
    source = {
        "namespace": list(namespace),
        "workspace": snapshot.header.workspace,
        "session_id": snapshot.header.session_id,
        "source_leaf_id": snapshot.leaf_id,
        "source_prefix_hash": data_hash([plain_data(e) for e in path]),
        "entry_ids": ids,
        "ranges": {key: list(ranges[key]) for key in ids},
        "start": {"entry_id": ids[0], "offset": ranges[ids[0]][0]},
        "end": {"entry_id": ids[-1], "offset": ranges[ids[-1]][1]},
    }
    if output_positions or end_output_positions:
        source.update(
            output_positions=output_positions or {},
            end_output_positions=end_output_positions or {},
        )
    from pycode.core.values import json_dumps

    return MemoryJob.model_validate_json(
        json_dumps(
            {
                **source,
                "job_id": data_hash(source),
                "run_id": run_id,
                "created_at": float(now),
                "updated_at": float(now),
                "output_positions": output_positions or {},
                "end_output_positions": end_output_positions or {},
            }
        )
    )
