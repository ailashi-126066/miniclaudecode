"""Worktrees stay isolated and are removed only after checked integration."""

import asyncio
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from tests.adapters.snapshot.test_git_tree import git, repository

from pycode.adapters.team.worktrees import WorktreeManager
from pycode.core.errors import ToolError


async def test_isolation_no_autocommit_and_dirty_or_cancelled_tree_retained(tmp_path):
    project = repository(tmp_path)
    source = project / "code.py"
    source.write_text("base\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "base")
    source.write_text("user uncommitted edit\n")
    head = git(project, "rev-parse", "HEAD")
    manager = WorktreeManager(project, tmp_path / "worktrees")
    record = await manager.create()
    worker = Path(record["path"])
    worker_source = worker / "code.py"
    assert worker_source.read_text() == "base\n"
    worker_source.write_text("worker edit\n")
    assert source.read_text() == "user uncommitted edit\n"
    assert git(project, "rev-parse", "HEAD") == head
    inspection = await manager.inspect(record["id"])
    assert inspection["dirty"] and "code.py" in inspection["diff"]
    cleanup = await manager.cleanup(record["id"])
    assert not cleanup["removed"] and await asyncio.to_thread(worker.exists)
    assert "dirty_or_missing" in cleanup["reasons"]
    with pytest.raises(ToolError, match="Require included"):
        await manager.mark_integration(record["id"], validated=True, worker_stopped=True)


async def test_checked_integration_clean_stopped_and_thirty_day_scan(tmp_path):
    project = repository(tmp_path)
    source = project / "code.py"
    source.write_text("base\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "base")
    manager = WorktreeManager(project, tmp_path / "worktrees")
    record = await manager.create()
    worker = Path(record["path"])
    # Explicit test integration is confined to this temporary repository.
    worker_source = worker / "code.py"
    worker_source.write_text("integrated\n")
    git(worker, "add", ".")
    git(worker, "commit", "-qm", "worker change")
    with pytest.raises(ToolError):
        await manager.mark_integration(record["id"], validated=True, worker_stopped=True)
    git(project, "merge", "--ff-only", record["branch"])
    inspection = await manager.inspect(record["id"])
    assert not inspection["dirty"], inspection["status_text"]
    await manager.mark_integration(record["id"], validated=True, worker_stopped=True)
    blocked = await manager.cleanup(record["id"], active=True)
    assert not blocked["removed"] and await asyncio.to_thread(worker.exists)
    now = datetime.now(UTC)
    report = await manager.scan(active_ids=(record["id"],), now=now)
    assert report["due"] and not report["results"][0]["removed"]
    assert not (await manager.scan(now=now + timedelta(days=29)))["due"]
    report = await manager.scan(now=now + timedelta(days=30))
    assert report["results"][0]["removed"] and not await asyncio.to_thread(worker.exists)
    assert report["results"][0]["branch_removed"]
    assert git(project, "branch", "--list", record["branch"]).strip() == b""
    assert source.read_text() == "integrated\n"


async def test_non_git_workspace_and_path_escape_rejected(tmp_path):
    manager = WorktreeManager(tmp_path, tmp_path / "worktrees")
    with pytest.raises(ToolError, match="Git repository"):
        await manager.create()
    with pytest.raises(ToolError, match="owned worktree ID"):
        manager.path("../user-data")


async def test_explicit_uncommitted_start_has_snapshot_without_commit_or_parent_changes(tmp_path):
    project = repository(tmp_path)
    tracked = project / "code.py"
    tracked.write_text("base\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "base")
    head = git(project, "rev-parse", "HEAD")
    tracked.write_text("dirty user code\n")
    created = project / "new.py"
    created.write_text("untracked user code\n")
    manager = WorktreeManager(project, tmp_path / "worktrees")
    record = await manager.create(include_uncommitted=True)
    worker = Path(record["path"])
    copied = worker / "code.py"
    copied_new = worker / "new.py"
    assert copied.read_text() == tracked.read_text()
    assert copied_new.read_text() == created.read_text()
    assert record["starting_snapshot"] and record["includes_uncommitted"]
    assert git(project, "rev-parse", "HEAD") == head
    assert not (await manager.cleanup(record["id"]))["removed"]
