from __future__ import annotations

from mewcode.plan_state import PlanStateStore, PlanStatus, StepStatus


def test_plan_state_persists_approval_and_verification(tmp_path):
    store = PlanStateStore(str(tmp_path))
    state = store.create("Implement durable plan state")
    approved = store.approve(
        state,
        "# Plan\n\n- [ ] Add persistence\n- [ ] Verify restart recovery\n",
    )

    assert approved.status is PlanStatus.APPROVED
    assert [step.description for step in approved.steps] == [
        "Add persistence",
        "Verify restart recovery",
    ]

    first = store.start_next(approved)
    assert first is not None and first.status is StepStatus.IN_PROGRESS
    store.complete_step(approved, first.id, ["pytest tests/test_plan_state.py"], ["mewcode/plan_state.py"])

    restored = store.load()
    assert restored is not None
    assert restored.status is PlanStatus.EXECUTING
    assert restored.steps[0].evidence.verification == ["pytest tests/test_plan_state.py"]


def test_plan_state_marks_complete_after_last_step(tmp_path):
    store = PlanStateStore(str(tmp_path))
    state = store.create("One step")
    state = store.approve(state, "- [ ] Done\n")
    step = store.start_next(state)
    assert step is not None
    store.complete_step(state, step.id)
    assert state.status is PlanStatus.COMPLETED
