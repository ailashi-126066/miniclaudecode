"""Durable structured execution plans for Plan mode.

The UI plan file remains the human-readable source of truth.  This module adds
machine-readable state so approval, execution and verification can survive a
restart and be inspected without asking the model to reconstruct its progress.
"""

from __future__ import annotations

import json
import re
import time
import uuid
from dataclasses import asdict, dataclass, field
from enum import StrEnum
from pathlib import Path


class PlanStatus(StrEnum):
    DRAFT = "draft"
    APPROVED = "approved"
    EXECUTING = "executing"
    COMPLETED = "completed"
    FAILED = "failed"


class StepStatus(StrEnum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"
    FAILED = "failed"


@dataclass
class PlanEvidence:
    verification: list[str] = field(default_factory=list)
    changed_files: list[str] = field(default_factory=list)
    failure_reason: str = ""


@dataclass
class PlanStep:
    id: str
    description: str
    status: StepStatus = StepStatus.PENDING
    requires_verification: bool = True
    evidence: PlanEvidence = field(default_factory=PlanEvidence)


@dataclass
class PlanState:
    id: str
    goal: str
    status: PlanStatus = PlanStatus.DRAFT
    steps: list[PlanStep] = field(default_factory=list)
    updated_at: float = field(default_factory=time.time)


class PlanStateStore:
    """Persists one active plan under the workspace's ignored .mewcode state."""

    def __init__(self, work_dir: str) -> None:
        self.path = Path(work_dir) / ".mewcode" / "plans" / "state.json"

    def create(self, goal: str) -> PlanState:
        state = PlanState(id=uuid.uuid4().hex[:12], goal=goal.strip())
        self.save(state)
        return state

    def load(self) -> PlanState | None:
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            steps = [
                PlanStep(
                    id=str(item["id"]),
                    description=str(item["description"]),
                    status=StepStatus(item.get("status", StepStatus.PENDING)),
                    requires_verification=bool(item.get("requires_verification", True)),
                    evidence=PlanEvidence(**item.get("evidence", {})),
                )
                for item in raw.get("steps", [])
            ]
            return PlanState(
                id=str(raw["id"]),
                goal=str(raw["goal"]),
                status=PlanStatus(raw.get("status", PlanStatus.DRAFT)),
                steps=steps,
                updated_at=float(raw.get("updated_at", time.time())),
            )
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def save(self, state: PlanState) -> None:
        state.updated_at = time.time()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(asdict(state), ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(self.path)

    def approve(self, state: PlanState, plan_text: str = "") -> PlanState:
        if state.status is PlanStatus.DRAFT:
            state.status = PlanStatus.APPROVED
        if plan_text and not state.steps:
            state.steps = _steps_from_plan_text(plan_text)
        self.save(state)
        return state

    def start_next(self, state: PlanState) -> PlanStep | None:
        if state.status is PlanStatus.APPROVED:
            state.status = PlanStatus.EXECUTING
        for step in state.steps:
            if step.status is StepStatus.PENDING:
                step.status = StepStatus.IN_PROGRESS
                self.save(state)
                return step
        return None

    def complete_step(
        self,
        state: PlanState,
        step_id: str,
        verification: list[str] | None = None,
        changed_files: list[str] | None = None,
    ) -> None:
        step = _find_step(state, step_id)
        step.status = StepStatus.COMPLETED
        step.evidence.verification = verification or []
        step.evidence.changed_files = changed_files or []
        if state.steps and all(item.status is StepStatus.COMPLETED for item in state.steps):
            state.status = PlanStatus.COMPLETED
        self.save(state)

    def fail_step(self, state: PlanState, step_id: str, reason: str) -> None:
        step = _find_step(state, step_id)
        step.status = StepStatus.FAILED
        step.evidence.failure_reason = reason
        state.status = PlanStatus.FAILED
        self.save(state)


def _find_step(state: PlanState, step_id: str) -> PlanStep:
    for step in state.steps:
        if step.id == step_id:
            return step
    raise KeyError(f"Plan step not found: {step_id}")


def _steps_from_plan_text(plan_text: str) -> list[PlanStep]:
    steps: list[PlanStep] = []
    for line in plan_text.splitlines():
        match = re.match(r"^\s*(?:[-*]\s*\[\s*\]|\d+[.)])\s+(.+?)\s*$", line)
        if not match:
            continue
        description = match.group(1)
        steps.append(PlanStep(id=f"step-{len(steps) + 1}", description=description))
    return steps
