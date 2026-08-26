from __future__ import annotations

import asyncio
import os
import re
import subprocess
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


MAX_DIFF_CHARS = 20_000
MAX_OUTPUT_CHARS = 8_000


def _trim(value: str, limit: int = MAX_OUTPUT_CHARS) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + "\n... (truncated)"


def _git(args: list[str], cwd: str, *, input_text: str | None = None) -> subprocess.CompletedProcess[str]:
    env = {**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_ASKPASS": ""}
    return subprocess.run(
        ["git", *args],
        cwd=cwd,
        input=input_text,
        capture_output=True,
        text=True,
        timeout=60,
        env=env,
    )


@dataclass
class ChangeReport:
    worktree_path: str
    branch: str
    base_commit: str
    head_commit: str = ""
    changed_files: list[str] = field(default_factory=list)
    diff: str = ""
    uncommitted_changes: int = 0
    error: str = ""

    def summary(self) -> str:
        if self.error:
            return f"Change report unavailable: {self.error}"
        files = ", ".join(self.changed_files) if self.changed_files else "(no files)"
        return (
            f"Branch: {self.branch}\nBase: {self.base_commit}\nHead: {self.head_commit}\n"
            f"Changed files: {files}\nUncommitted entries: {self.uncommitted_changes}"
        )


def collect_change_report(worktree_path: str, branch: str, base_commit: str) -> ChangeReport:
    report = ChangeReport(worktree_path=worktree_path, branch=branch, base_commit=base_commit)
    try:
        head = _git(["rev-parse", "HEAD"], worktree_path)
        if head.returncode != 0:
            report.error = _trim(head.stderr.strip() or "unable to read HEAD")
            return report
        report.head_commit = head.stdout.strip()

        status = _git(["status", "--porcelain"], worktree_path)
        if status.returncode == 0:
            entries = [line for line in status.stdout.splitlines() if line.strip()]
            report.uncommitted_changes = len(entries)
            report.changed_files.extend(line[3:] for line in entries if len(line) > 3)

        if base_commit:
            names = _git(["diff", "--name-only", base_commit], worktree_path)
            if names.returncode == 0:
                report.changed_files.extend(line for line in names.stdout.splitlines() if line.strip())
            diff = _git(["diff", "--binary", base_commit], worktree_path)
            if diff.returncode == 0:
                report.diff = _trim(diff.stdout, MAX_DIFF_CHARS)

        report.changed_files = sorted(set(report.changed_files))
        return report
    except (OSError, subprocess.SubprocessError) as exc:
        report.error = str(exc)
        return report


@dataclass
class IntegrationOutcome:
    status: str
    integration_branch: str = ""
    integration_path: str = ""
    message: str = ""
    verification_command: list[str] = field(default_factory=list)
    verification_output: str = ""
    verified: bool = False
    conflict_files: list[str] = field(default_factory=list)
    conflict_blocks: str = ""
    source_branch: str = ""
    source_commit: str = ""

    def summary(self) -> str:
        lines = [
            f"Integration status: {self.status}",
            f"Integration branch: {self.integration_branch}",
            f"Source branch: {self.source_branch}",
            f"Source commit: {self.source_commit}",
        ]
        if self.message:
            lines.append(f"Message: {self.message}")
        if self.verification_command:
            lines.append("Verification: " + " ".join(self.verification_command))
            lines.append("Verification output:\n" + self.verification_output)
        if self.conflict_files:
            lines.append("Conflict files: " + ", ".join(self.conflict_files))
            lines.append("Conflict blocks:\n" + self.conflict_blocks)
        return "\n".join(lines)


class IntegrationManager:
    """Integrates completed worktree tasks in a dedicated, disposable Git worktree.

    The original branch is never checked out or mutated by this class. Successful
    integrations are committed to an `integration-*` branch for the lead to review.
    """

    def __init__(self, repo_root: str, session_id: str = "") -> None:
        self.repo_root = str(Path(repo_root).resolve())
        suffix = re.sub(r"[^a-z0-9-]", "-", session_id.lower())[-24:] or uuid.uuid4().hex[:8]
        self.branch = f"integration-{suffix}"
        self.path = str(Path(self.repo_root) / ".mewcode" / "integrations" / self.branch)
        self._base_ref = "HEAD"
        self._ready = False
        self._lock = asyncio.Lock()

    async def integrate(self, task: Any) -> IntegrationOutcome:
        async with self._lock:
            return await asyncio.to_thread(self._integrate_sync, task)

    def _ensure_worktree(self) -> str | None:
        if self._ready:
            return None
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        result = _git(["worktree", "add", "-B", self.branch, self.path, self._base_ref], self.repo_root)
        if result.returncode != 0:
            return _trim(result.stderr.strip() or "git worktree add failed")
        self._ready = True
        return None

    def _snapshot_source(self, task: Any) -> str | None:
        status = _git(["status", "--porcelain"], task.worktree_path)
        if status.returncode != 0:
            return _trim(status.stderr.strip() or "unable to inspect source worktree")
        if not status.stdout.strip():
            return None
        add = _git(["add", "-A"], task.worktree_path)
        if add.returncode != 0:
            return _trim(add.stderr.strip() or "git add failed")
        commit = _git(["commit", "-m", f"agent task {task.id}: {task.name}"], task.worktree_path)
        if commit.returncode != 0:
            return _trim(commit.stderr.strip() or "git commit failed")
        return None

    def _abort_merge(self) -> None:
        _git(["merge", "--abort"], self.path)

    def _integrate_sync(self, task: Any) -> IntegrationOutcome:
        report = task.change_report or collect_change_report(
            task.worktree_path, task.worktree_branch, task.base_commit
        )
        task.change_report = report
        source_branch = task.worktree_branch or report.branch
        if not source_branch:
            return IntegrationOutcome(status="failed", message="source branch is missing")
        if report.error:
            return IntegrationOutcome(status="failed", message=report.error, source_branch=source_branch)

        snapshot_error = self._snapshot_source(task)
        if snapshot_error:
            return IntegrationOutcome(
                status="failed", message=snapshot_error, source_branch=source_branch, source_commit=report.head_commit
            )
        report = collect_change_report(task.worktree_path, source_branch, task.base_commit)
        task.change_report = report

        setup_error = self._ensure_worktree()
        if setup_error:
            return IntegrationOutcome(status="failed", message=setup_error, source_branch=source_branch, source_commit=report.head_commit)

        merge = _git(["merge", "--no-commit", "--no-ff", source_branch], self.path)
        if merge.returncode != 0:
            files = _git(["diff", "--name-only", "--diff-filter=U"], self.path)
            blocks = _git(["diff", "--cc"], self.path)
            outcome = IntegrationOutcome(
                status="conflict",
                integration_branch=self.branch,
                integration_path=self.path,
                message=_trim(merge.stderr.strip() or merge.stdout.strip() or "merge conflict"),
                conflict_files=[line for line in files.stdout.splitlines() if line.strip()],
                conflict_blocks=_trim(blocks.stdout, MAX_DIFF_CHARS),
                source_branch=source_branch,
                source_commit=report.head_commit,
            )
            self._abort_merge()
            return outcome

        verification = list(task.verification_command or [])
        verified = False
        verification_output = ""
        if verification:
            try:
                check = subprocess.run(
                    verification,
                    cwd=self.path,
                    capture_output=True,
                    text=True,
                    timeout=600,
                    env={**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_ASKPASS": ""},
                )
            except (OSError, subprocess.TimeoutExpired) as exc:
                self._abort_merge()
                return IntegrationOutcome(
                    status="verification_failed",
                    integration_branch=self.branch,
                    integration_path=self.path,
                    message=f"verification command could not run: {exc}",
                    verification_command=verification,
                    source_branch=source_branch,
                    source_commit=report.head_commit,
                )
            verification_output = _trim((check.stdout or "") + (check.stderr or ""))
            if check.returncode != 0:
                self._abort_merge()
                return IntegrationOutcome(
                    status="verification_failed",
                    integration_branch=self.branch,
                    integration_path=self.path,
                    message=f"verification command exited {check.returncode}",
                    verification_command=verification,
                    verification_output=verification_output,
                    source_branch=source_branch,
                    source_commit=report.head_commit,
                )
            verified = True

        staged = _git(["diff", "--cached", "--quiet"], self.path)
        if staged.returncode == 0:
            return IntegrationOutcome(
                status="integrated" if verified else "integrated_unverified",
                integration_branch=self.branch,
                integration_path=self.path,
                message="source branch was already included in the integration branch",
                verification_command=verification,
                verification_output=verification_output,
                verified=verified,
                source_branch=source_branch,
                source_commit=report.head_commit,
            )

        commit = _git(["commit", "-m", f"integrate task {task.id}: {task.name}"], self.path)
        if commit.returncode != 0:
            self._abort_merge()
            return IntegrationOutcome(
                status="failed",
                integration_branch=self.branch,
                integration_path=self.path,
                message=_trim(commit.stderr.strip() or "integration commit failed"),
                verification_command=verification,
                verification_output=verification_output,
                source_branch=source_branch,
                source_commit=report.head_commit,
            )

        return IntegrationOutcome(
            status="integrated" if verified else "integrated_unverified",
            integration_branch=self.branch,
            integration_path=self.path,
            message="merged into integration branch",
            verification_command=verification,
            verification_output=verification_output,
            verified=verified,
            source_branch=source_branch,
            source_commit=report.head_commit,
        )
