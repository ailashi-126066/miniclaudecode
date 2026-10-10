from pathlib import Path
from mewcode.plan_state import PlanStateStore, PlanStatus, StepStatus


def test_start_step_persists_explicit_step_state(tmp_path: Path):
    store = PlanStateStore(str(tmp_path))
    state = store.create("Ship feature")
    state = store.approve(state, "1. Add feature\n2. Test feature")

    step = store.start_step(state, "step-2")
    restored = store.load()

    assert step.status is StepStatus.IN_PROGRESS
    assert restored is not None
    assert restored.status is PlanStatus.EXECUTING
    assert restored.steps[1].status is StepStatus.IN_PROGRESS
