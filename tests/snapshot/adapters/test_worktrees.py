"""Worktrees stay isolated and are removed only after checked integration."""

import asyncio
import os
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from pycode.kernel.domain.errors import ToolError
from pycode.snapshot.adapters.git_tree import GitSnapshotStore
from pycode.team.worktrees import WorktreeManager
from tests.snapshot.adapters.test_git_tree import git, repository


async def test_isolation_no_autocommit_and_dirty_or_cancelled_tree_retained(tmp_path):
    project = repository(tmp_path)
    source = project / "code.py"
    source.write_text("base\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "base")
    source.write_text("user uncommitted edit\n")
    head = git(project, "rev-parse", "HEAD")
    manager = WorktreeManager(project, tmp_path / "worktrees", snapshot_factory=GitSnapshotStore)
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
    manager = WorktreeManager(project, tmp_path / "worktrees", snapshot_factory=GitSnapshotStore)
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
    manager = WorktreeManager(tmp_path, tmp_path / "worktrees", snapshot_factory=GitSnapshotStore)
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
    manager = WorktreeManager(project, tmp_path / "worktrees", snapshot_factory=GitSnapshotStore)
    record = await manager.create(include_uncommitted=True)
    worker = Path(record["path"])
    copied = worker / "code.py"
    copied_new = worker / "new.py"
    assert copied.read_text() == tracked.read_text()
    assert copied_new.read_text() == created.read_text()
    assert record["starting_snapshot"] and record["includes_uncommitted"]
    assert git(project, "rev-parse", "HEAD") == head
    assert not (await manager.cleanup(record["id"]))["removed"]


async def team_repository(tmp_path):
    project = repository(tmp_path)
    (project / "code.py").write_text("base\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "base")
    manager = WorktreeManager(project, tmp_path / "worktrees", snapshot_factory=GitSnapshotStore)
    record = await manager.create()
    return project, manager, record, Path(record["path"])


async def test_review_covers_untracked_contents_and_index_changes(tmp_path):
    _, manager, record, worker = await team_repository(tmp_path)
    (worker / "a\nnew.py").write_text("first version\n")
    first = await manager.changes(record["id"])
    assert first["files"] == ["a\nnew.py"]
    assert first["untracked"][0]["size"] == 14
    assert "first version" in first["diff"]
    (worker / "a\nnew.py").write_text("other version\n")
    second = await manager.changes(record["id"])
    assert first["fingerprint"] != second["fingerprint"]
    git(worker, "add", ".")
    staged = await manager.changes(record["id"])
    assert staged["fingerprint"] != second["fingerprint"]
    assert "other version" in staged["diff"]


async def test_review_truncates_display_but_hashes_complete_large_files(tmp_path):
    _, manager, record, worker = await team_repository(tmp_path)
    path = worker / "large.txt"
    path.write_text("x" * 100000)
    first = await manager.changes(record["id"], limit=40)
    assert len(first["diff"]) <= 40 and first["diff_truncated"]
    path.write_text("x" * 99999 + "y")
    second = await manager.changes(record["id"], limit=40)
    assert first["fingerprint"] != second["fingerprint"]


async def test_review_symlink_does_not_read_its_destination(tmp_path):
    _, manager, record, worker = await team_repository(tmp_path)
    secret = tmp_path / "outside.txt"
    secret.write_text("do not include these bytes")
    try:
        (worker / "outside-link").symlink_to(secret)
    except OSError:
        pytest.skip("Symlinks are unavailable")
    review = await manager.changes(record["id"])
    assert review["untracked"][0]["mode"] == "symlink"
    assert "do not include these bytes" not in review["diff"]


async def test_merge_requires_current_review_and_revalidates_approved_source(tmp_path):
    project, manager, record, worker = await team_repository(tmp_path)
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    head = git(project, "rev-parse", "HEAD")
    (worker / "code.py").write_text("changed after approval\n")
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code in {"review_stale", "merge_preview_stale"}
    assert git(project, "rev-parse", "HEAD") == head
    assert (project / "code.py").read_text() == "base\n"


async def test_merge_revalidates_target_head_and_branch(tmp_path):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("worker\n")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    git(project, "commit", "--allow-empty", "-qm", "new target commit")
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "merge_preview_stale"
    assert (project / "code.py").read_text() == "base\n"


async def test_dirty_target_rejected_before_git_side_effects(tmp_path):
    project, manager, record, worker = await team_repository(tmp_path)
    review = await manager.changes(record["id"])
    (project / "untracked.txt").write_text("user work\n")
    source_head = git(worker, "rev-parse", "HEAD")
    with pytest.raises(ToolError) as failed:
        await manager.merge_preview(record["id"], review["fingerprint"])
    assert failed.value.code == "merge_target_dirty"
    assert git(worker, "rev-parse", "HEAD") == source_head
    assert (project / "untracked.txt").read_text() == "user work\n"


async def test_reviewed_merge_receipt_precedes_cleanup_without_claiming_tests_passed(tmp_path):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("reviewed worker change\n")
    (worker / "new.py").write_text("new file\n")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    assert preview["hooks_disabled"] and preview["target_head"]
    result = await manager.merge_into_workspace(record["id"], preview)
    assert result["merged"] and not result["empty"]
    assert worker.exists() and (project / "new.py").read_text() == "new file\n"
    with pytest.raises(ToolError, match="matching successful"):
        await manager.complete_team_merge(record["id"], "0" * 40)
    cleaned = await manager.complete_team_merge(record["id"], result["merge_commit"])
    assert cleaned["removed"] and not worker.exists()
    stored = manager.read(record["id"])
    assert stored["integration_verified"] and not stored.get("validated", False)
    assert git(project, "branch", "--list", record["branch"]).strip() == b""


async def test_empty_reviewed_merge_can_be_cleaned(tmp_path):
    _, manager, record, worker = await team_repository(tmp_path)
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    result = await manager.merge_into_workspace(record["id"], preview)
    assert result["merged"] and result["empty"] and worker.exists()
    assert (await manager.complete_team_merge(record["id"], result["merge_commit"]))["removed"]


async def test_conflict_restores_target_and_resolution_is_confined_to_worker(tmp_path):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("worker version\n")
    (project / "code.py").write_text("target version\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "target edit")
    target_head = git(project, "rev-parse", "HEAD")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    conflict = await manager.merge_into_workspace(record["id"], preview)
    assert conflict["conflicts"] == ["code.py"] and conflict["target_restored"]
    assert git(project, "rev-parse", "HEAD") == target_head
    assert git(project, "status", "--porcelain").strip() == b""
    assert (project / "code.py").read_text() == "target version\n"
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(
        record["id"], review["fingerprint"], action="prepare_resolution"
    )
    prepared = await manager.merge_into_workspace(
        record["id"], preview, action="prepare_resolution"
    )
    assert prepared["resolution_pending"] and prepared["conflicts"] == ["code.py"]
    assert manager.merge_head(workspace=worker) and manager.merge_head() is None
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "resolution_required"
    (worker / "code.py").write_text("reviewed combined version\n")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    result = await manager.merge_into_workspace(record["id"], preview)
    assert result["merged"] and (project / "code.py").read_text() == "reviewed combined version\n"
    assert (await manager.complete_team_merge(record["id"], result["merge_commit"]))["removed"]


async def test_conflict_abort_failure_is_reported_without_cleanup(tmp_path, monkeypatch):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("worker version\n")
    (project / "code.py").write_text("target version\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "target edit")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    original = manager.git

    def fail_abort(arguments, **options):
        if arguments == ["merge", "--abort"]:
            raise ToolError("worktree_git_error", "simulated abort failure")
        return original(arguments, **options)

    monkeypatch.setattr(manager, "git", fail_abort)
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "merge_recovery_required"
    assert worker.exists() and manager.merge_head()
    assert manager.read(record["id"])["merge_recovery_required"]


async def test_source_edit_between_approval_check_and_staging_cannot_reach_target(
    tmp_path, monkeypatch
):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("approved\n")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    original = manager.git

    def edit_before_staging(arguments, **options):
        if arguments == ["add", "-A", "--", "."] and options.get("index") is None:
            (worker / "code.py").write_text("unapproved concurrent edit\n")
        return original(arguments, **options)

    monkeypatch.setattr(manager, "git", edit_before_staging)
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "merge_preview_stale"
    assert (project / "code.py").read_text() == "base\n"
    assert worker.exists()


async def test_external_target_checkout_after_git_merge_cannot_be_reported_as_success(
    tmp_path, monkeypatch
):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("approved\n")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    original = manager.git

    def checkout_after_merge(arguments, **options):
        result = original(arguments, **options)
        if "merge" in arguments and "--no-edit" in arguments:
            git(project, "checkout", "-qb", "unexpected-target")
        return result

    monkeypatch.setattr(manager, "git", checkout_after_merge)
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "merge_recovery_required"
    assert not manager.read(record["id"]).get("last_team_merge")
    assert manager.read(record["id"])["merge_recovery_required"] and worker.exists()


async def test_timeout_after_actual_merge_keeps_recovery_evidence_and_worktree(
    tmp_path, monkeypatch
):
    _, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("approved\n")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    original = manager.git

    def timeout_after_merge(arguments, **options):
        result = original(arguments, **options)
        if "merge" in arguments and "--no-edit" in arguments:
            raise ToolError("worktree_git_timeout", "simulated timeout after commit")
        return result

    monkeypatch.setattr(manager, "git", timeout_after_merge)
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "merge_recovery_required"
    assert worker.exists() and manager.read(record["id"])["merge_recovery_required"]


async def test_timeout_during_conflicted_merge_restores_clean_target(tmp_path, monkeypatch):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("worker version\n")
    (project / "code.py").write_text("target version\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "target edit")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    original = manager.git

    def timeout_in_conflict(arguments, **options):
        result = original(arguments, **options)
        if "merge" in arguments and "--no-edit" in arguments:
            raise ToolError("worktree_git_timeout", "simulated timeout during merge")
        return result

    monkeypatch.setattr(manager, "git", timeout_in_conflict)
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "worktree_git_timeout"
    assert manager.merge_head() is None and worker.exists()
    assert git(project, "status", "--porcelain").strip() == b""
    assert manager.read(record["id"])["timeout_target_restored"]


def test_git_timeout_has_a_stable_error_code(tmp_path, monkeypatch):
    manager = WorktreeManager(tmp_path, tmp_path / "worktrees", snapshot_factory=GitSnapshotStore)

    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired("git", 60)

    monkeypatch.setattr("pycode.team.worktrees.subprocess.run", timeout)
    with pytest.raises(ToolError) as failed:
        manager.git(["status"])
    assert failed.value.code == "worktree_git_timeout"


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="FIFO is unavailable")
async def test_untracked_fifo_is_never_opened_for_reading(tmp_path, monkeypatch):
    _, manager, record, worker = await team_repository(tmp_path)
    os.mkfifo(worker / "pipe")
    original = manager.git

    def include_fifo(arguments, **options):
        if arguments[:1] == ["ls-files"] and "--others" in arguments:
            return b"pipe\0", 0
        return original(arguments, **options)

    monkeypatch.setattr(manager, "git", include_fifo)
    with pytest.raises(ToolError) as failed:
        await manager.changes(record["id"])
    assert failed.value.code == "unsupported_worktree_file"


async def test_review_does_not_modify_worker_index(tmp_path):
    _, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("staged\n")
    git(worker, "add", ".")
    (worker / "code.py").write_text("working\n")
    before = git(worker, "diff", "--cached", "--binary")
    reviewed = await manager.changes(record["id"])
    assert reviewed["source_tree"] and "working" in reviewed["diff"]
    assert git(worker, "diff", "--cached", "--binary") == before


async def test_prepare_resolution_timeout_preserves_worker_merge_recovery(tmp_path, monkeypatch):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("worker version\n")
    (project / "code.py").write_text("target version\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "target edit")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(
        record["id"], review["fingerprint"], action="prepare_resolution"
    )
    original = manager.git

    def timeout_after_preparation(arguments, **options):
        result = original(arguments, **options)
        if "merge" in arguments and "--no-commit" in arguments:
            raise ToolError("worktree_git_timeout", "simulated preparation timeout")
        return result

    monkeypatch.setattr(manager, "git", timeout_after_preparation)
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview, action="prepare_resolution")
    assert failed.value.code == "merge_recovery_required"
    stored = manager.read(record["id"])
    assert stored["source_recovery_required"] and stored["merge_recovery_required"]
    assert stored["resolution_pending"]["conflicts"] == ["code.py"]
    assert manager.merge_head(workspace=worker) and manager.merge_head() is None
    assert not stored.get("timeout_target_restored")


async def test_same_tree_with_unapproved_source_history_cannot_be_merged(tmp_path, monkeypatch):
    project, manager, record, worker = await team_repository(tmp_path)
    (worker / "code.py").write_text("approved\n")
    review = await manager.changes(record["id"])
    preview = await manager.merge_preview(record["id"], review["fingerprint"])
    original = manager.git

    def add_unreviewed_commit(arguments, **options):
        result = original(arguments, **options)
        if "commit" in arguments and "Integrate reviewed agent changes" in arguments:
            git(worker, "commit", "--allow-empty", "-qm", "external history change")
        return result

    monkeypatch.setattr(manager, "git", add_unreviewed_commit)
    with pytest.raises(ToolError) as failed:
        await manager.merge_into_workspace(record["id"], preview)
    assert failed.value.code == "merge_preview_stale"
    assert (project / "code.py").read_text() == "base\n" and worker.exists()


async def test_configured_git_identity_is_not_overridden(tmp_path):
    _, manager, _, worker = await team_repository(tmp_path)
    git(worker, "config", "user.name", "Existing User")
    git(worker, "config", "user.email", "existing@example.test")
    options = manager.identity_options(workspace=worker)
    assert not any(item.startswith(("user.name=", "user.email=")) for item in options)
