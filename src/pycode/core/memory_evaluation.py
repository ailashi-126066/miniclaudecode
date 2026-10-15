"""Comparison receipts bind exact rule values, original versions and completed steps."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from pycode.core.memory_schema import Identity, MemoryValue
from pycode.core.summary_source import data_hash

Digest = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
CASE_IDS = (
    "parameter-source",
    "mutable-argument",
    "minimal-example",
    "debugging-explanation",
    "current-instruction",
    "truthful-verification",
)


class EvaluationAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    text: Annotated[str, Field(min_length=1, max_length=1200)]
    sha256: Digest
    request_id: Identity

    @model_validator(mode="after")
    def integrity(self):
        if self.sha256 != data_hash(self.text):
            raise ValueError("Evaluation answer checksum differs")
        return self


class EvaluationJudgment(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    relevant: bool
    before_score: Annotated[int, Field(ge=0, le=4)]
    after_score: Annotated[int, Field(ge=0, le=4)]
    constraints_ok: bool
    request_id: Identity


class EvaluationCase(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    case_id: Literal[
        "parameter-source",
        "mutable-argument",
        "minimal-example",
        "debugging-explanation",
        "current-instruction",
        "truthful-verification",
    ]
    before: EvaluationAnswer | None = None
    after: EvaluationAnswer | None = None
    judgment: EvaluationJudgment | None = None

    @model_validator(mode="after")
    def order(self):
        if self.after is not None and self.before is None:
            raise ValueError("Candidate evaluation precedes its baseline")
        if self.judgment is not None and (self.before is None or self.after is None):
            raise ValueError("A comparison needs both completed answers")
        return self


class MemoryRuleEvaluation(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    evaluation_id: Digest
    namespace: tuple[Identity, ...]
    batch_id: Identity
    request_run_id: Identity
    source_prefix_hash: Digest
    dataset_sha256: Digest
    model_signature: Digest
    policy_version: Literal["behavior-v1"] = "behavior-v1"
    desired_behavior: Annotated[str, Field(min_length=1, max_length=16000)]
    applicability: Annotated[list[str], Field(min_length=1, max_length=16)]
    values_sha256: Annotated[dict[Identity, Digest], Field(min_length=1, max_length=8)]
    previous_sha256: dict[Identity, Digest]
    baseline_values: dict[Identity, MemoryValue | None]
    unchanged: bool = False
    cases: Annotated[list[EvaluationCase], Field(max_length=6)] = Field(default_factory=list)

    def identity(self):
        return self.model_dump(mode="json", exclude={"evaluation_id", "cases"})

    @model_validator(mode="after")
    def integrity(self):
        keys = set(self.values_sha256)
        if (
            not self.namespace
            or keys != set(self.previous_sha256)
            or keys != set(self.baseline_values)
            or self.evaluation_id != data_hash(self.identity())
        ):
            raise ValueError("Evaluation identity or target bindings differ")
        if self.unchanged and self.cases:
            raise ValueError("An unchanged rule has no comparison requests")
        if len({case.case_id for case in self.cases}) != len(self.cases):
            raise ValueError("Evaluation contains duplicate cases")
        requests = []
        for case in self.cases:
            for part in (case.before, case.after, case.judgment):
                if part is not None:
                    requests.append(part.request_id)
        if len(set(requests)) != len(requests):
            raise ValueError("Evaluation reused a request across different steps")
        return self

    @property
    def complete(self):
        if self.unchanged:
            return True
        return set(case.case_id for case in self.cases) == set(CASE_IDS) and all(
            case.judgment is not None for case in self.cases
        )

    @property
    def accepted(self):
        if self.unchanged or not self.complete:
            return False
        relevant = [case for case in self.cases if case.judgment.relevant]
        return (
            len(relevant) >= 2
            and all(case.judgment.constraints_ok for case in self.cases)
            and all(case.judgment.after_score >= case.judgment.before_score for case in self.cases)
            and sum(case.judgment.after_score - case.judgment.before_score for case in relevant)
            >= 2
            and next(
                case for case in self.cases if case.case_id == "current-instruction"
            ).after.text.strip()
            == "4"
        )
