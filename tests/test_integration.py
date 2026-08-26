from __future__ import annotations

import asyncio
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

from mewcode.integration import IntegrationManager, collect_change_report
from mewcode.agents.task_manager import TaskManager
from mewcode.plan_state import PlanStateStore, PlanStatus, StepStatus


def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=repo, capture_output=True, text=True, check=True
    )
    return result.stdout.strip()


def _repo(tmp_path: Path) -> tuple[Path, str]:
    repo = tmp_path / "repo"
    repo.mkdir()
    _git(repo, "init")
    _git(repo, "config", "user.email", "test@example.com")
    _git(repo, "config", "user.name", "Test User")
    (repo / "file.txt").write_text("base\n", encoding="utf-8")
    _git(repo, "add", "file.txt")
    _git(repo, "commit", "-m", "base")
    return repo, _git(repo, "rev-parse", "HEAD")


def _task(task_id: str, name: str, wt: Path, branch: str, base: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=task_id,
        name=name,
        worktree_path=str(wt),
        worktree_branch=branch,
        base_commit=base,
        verification_command=[sys.executable, "-c", "print('verified')"],
        change_report=collect_change_report(str(wt), branch, base),
    )


def test_integration_snapshots_uncommitted_worktree_and_runs_verification(tmp_path: Path):
    repo, base = _repo(tmp_path)
    wt = tmp_path / "worker"
    _git(repo, "worktree", "add", "-b", "worker-a", str(wt))
    (wt / "file.txt").write_text("worker change\n", encoding="utf-8")

    manager = IntegrationManager(str(repo), "test")
    task = _task("task-a", "worker-a", wt, "worker-a", base)
    outcome = asyncio.run(manager.integrate(task))

    assert outcome.status == "integrated"
    assert outcome.verified is True
    assert (Path(outcome.integration_path) / "file.txt").read_text(encoding="utf-8") == "worker change\n"
    assert task.change_report.head_commit


def test_integration_reports_conflicting_files_and_source_branches(tmp_path: Path):
    repo, base = _repo(tmp_path)
    wt_a = tmp_path / "worker-a"
    wt_b = tmp_path / "worker-b"
    _git(repo, "worktree", "add", "-b", "worker-a", str(wt_a))
    _git(repo, "worktree", "add", "-b", "worker-b", str(wt_b))
    (wt_a / "file.txt").write_text("change from a\n", encoding="utf-8")
    (wt_b / "file.txt").write_text("change from b\n", encoding="utf-8")

    manager = IntegrationManager(str(repo), "conflict")
    first = asyncio.run(manager.integrate(_task("task-a", "worker-a", wt_a, "worker-a", base)))
    second = asyncio.run(manager.integrate(_task("task-b", "worker-b", wt_b, "worker-b", base)))

    assert first.status == "integrated"
    assert second.status == "conflict"
    assert second.source_branch == "worker-b"
    assert second.conflict_files == ["file.txt"]


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


def test_task_manager_attaches_integration_outcome_before_notification(tmp_path: Path):
    repo, base = _repo(tmp_path)
    wt = tmp_path / "worker"
    _git(repo, "worktree", "add", "-b", "worker-a", str(wt))
    (wt / "file.txt").write_text("worker change\n", encoding="utf-8")

    class FakeAgent:
        team_name = ""
        _team_manager = None
        total_input_tokens = 0
        total_output_tokens = 0

        async def run_to_completion(self, task: str) -> str:
            return "done"

    async def run_task():
        manager = TaskManager(IntegrationManager(str(repo), "task-manager"))
        task_id = manager.launch(
            agent=FakeAgent(),
            task="change file",
            name="worker-a",
            worktree_path=str(wt),
            worktree_branch="worker-a",
            base_commit=base,
            verification_command=[sys.executable, "-c", "print('verified')"],
        )
        await manager._async_tasks[task_id]
        return manager.poll_completed()[0]

    completed = asyncio.run(run_task())

    assert completed.status == "completed"
    assert completed.integration_outcome.status == "integrated"
    assert completed.integration_outcome.verified is True
