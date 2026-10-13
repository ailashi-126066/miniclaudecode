"""Checked Git worktree creation and conservative post-integration cleanup."""

import asyncio
import json
import os
import re
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from pycode.adapters.snapshot.git_tree import GitSnapshotStore
from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.tools.backend import atomic_write, owned_io
from pycode.core.errors import ToolError
from pycode.core.values import json_dumps


class WorktreeManager:
    def __init__(self, workspace, directory):
        self.workspace = Path(workspace).resolve()
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = asyncio.Lock()
        self.lease = FileLease(self.directory / "worktrees.lock")

    def git(self, arguments, *, workspace=None, allowed=(0,), data=None):
        environment = {}
        for key, value in os.environ.items():
            if not key.startswith("GIT_"):
                environment[key] = value
        # Worktrees use the repository's ordinary clean/smudge and line-ending
        # rules. Snapshot object storage deliberately has a different policy.
        result = subprocess.run(
            [
                "git",
                "-c",
                "core.hooksPath=" + str(self.directory / "disabled-hooks"),
                "-c",
                "core.fsmonitor=false",
                "-C",
                str(workspace or self.workspace),
                *arguments,
            ],
            input=data,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
            timeout=60,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        if result.returncode not in allowed:
            raise ToolError("worktree_git_error", result.stderr.decode("utf-8", "replace"))
        return result.stdout, result.returncode

    def path(self, identifier):
        if not re.fullmatch(r"[0-9a-f]{32}", identifier):
            raise ToolError("invalid_worktree", "Expected an owned worktree ID")
        path = (self.directory / identifier).resolve()
        if path.parent != self.directory or path == self.workspace:
            raise ToolError("worktree_path_escape", "Worktree path is outside its owned directory")
        return path

    def record_path(self, identifier):
        self.path(identifier)
        return self.directory / (identifier + ".json")

    def save(self, record):
        atomic_write(
            self.record_path(record["id"]), json_dumps(record, ensure_ascii=False).encode()
        )

    async def update(self, record):
        async with self.lock:
            await owned_io(self.save, record)

    async def create(self, *, base="HEAD", include_uncommitted=False):
        async with self.lock:
            captured = None
            snapshots = None
            if include_uncommitted:
                snapshots = GitSnapshotStore(
                    self.workspace,
                    self.directory / "starting-snapshots",
                    excluded_roots=(self.directory,),
                )
                captured = await snapshots.capture()
                if captured is None:
                    raise ToolError("worktree_requires_git", "Cannot capture a non-Git workspace")
            record = await owned_io(self.create_sync, base)
            if captured is not None:
                await owned_io(self.materialize_snapshot, record, snapshots, captured)
            return record

    def materialize_snapshot(self, record, snapshots, captured):
        destination = self.path(record["id"])
        tracked, _ = self.git(["ls-files", "-z"], workspace=destination)
        for raw in tracked.split(b"\0"):
            if not raw:
                continue
            name = os.fsdecode(raw)
            target = (destination / name).resolve()
            if not target.is_relative_to(destination):
                raise ToolError("worktree_path_escape", "Tracked path escapes Worker worktree")
            if name not in captured.files:
                target.unlink(missing_ok=True)
        for name, version in captured.files.items():
            target = destination / name
            if not target.resolve().is_relative_to(destination):
                raise ToolError("worktree_path_escape", "Snapshot path escapes Worker worktree")
            content, _ = snapshots.git(["cat-file", "blob", version.object_id], own=True)
            target.parent.mkdir(parents=True, exist_ok=True)
            if version.mode == "120000":
                target.unlink(missing_ok=True)
                target.symlink_to(os.fsdecode(content))
            else:
                atomic_write(target, content)
                if os.name != "nt":
                    target.chmod(0o755 if version.mode == "100755" else 0o644)
        record["starting_snapshot"] = captured.tree_id
        record["starting_snapshot_skipped"] = list(captured.skipped)
        record["includes_uncommitted"] = True
        self.save(record)

    def create_sync(self, base):
        if not isinstance(base, str) or not base or base.startswith("-"):
            raise ToolError("invalid_worktree_base", "Expected a Git revision")
        self.lease.acquire()
        try:
            root, status = self.git(["rev-parse", "--show-toplevel"], allowed=(0, 128))
            if status != 0:
                raise ToolError(
                    "worktree_requires_git", "Modifying Workers require a Git repository"
                )
            commit, _ = self.git(["rev-parse", "--verify", base + "^{commit}"])
            identifier = uuid4().hex
            path = self.path(identifier)
            branch = "pycode/team/" + identifier
            record = {
                "id": identifier,
                "path": str(path),
                "branch": branch,
                "repository": os.fsdecode(root.strip()),
                "base": commit.decode().strip(),
                "created_at": datetime.now(UTC).isoformat(),
                "integrated": False,
                "validated": False,
                "worker_stopped": False,
                "status": "creating",
            }
            self.save(record)
            try:
                self.git(["worktree", "add", "-b", branch, str(path), record["base"]])
            except ToolError:
                record["status"] = "creation_failed"
                self.save(record)
                raise
            record["status"] = "retained"
            self.save(record)
            return record
        finally:
            self.lease.close()

    def read(self, identifier):
        try:
            record = json.loads(self.record_path(identifier).read_bytes())
        except FileNotFoundError as error:
            raise ToolError("worktree_not_found", "Owned worktree record does not exist") from error
        if (
            record["path"] != str(self.path(identifier))
            or record["id"] != identifier
            or record["branch"] != "pycode/team/" + identifier
        ):
            raise ToolError(
                "invalid_worktree_record", "Worktree record does not match its owned path"
            )
        return record

    async def inspect(self, identifier):
        return await owned_io(self.inspect_sync, identifier)

    def inspect_sync(self, identifier):
        record = self.read(identifier)
        path = self.path(identifier)
        if not path.exists():
            return {**record, "dirty": None, "diff": "", "missing": True}
        status, _ = self.git(["status", "--porcelain=v1", "-z"], workspace=path)
        diff, _ = self.git(["diff", "--stat", "HEAD"], workspace=path)
        return {
            **record,
            "dirty": bool(status),
            "status_text": status.decode("utf-8", "replace"),
            "diff": diff.decode("utf-8", "replace"),
        }

    async def mark_integration(self, identifier, *, validated, worker_stopped):
        async with self.lock:
            record = await self.inspect(identifier)
            path = self.path(identifier)
            included = await owned_io(self.inclusion_status, record["branch"])
            if included != 0 or record["dirty"] or not validated or not worker_stopped:
                raise ToolError(
                    "worktree_not_integrated",
                    "Require included commits, passed validation, stopped Worker and clean files",
                )
            if not path.is_dir():
                raise ToolError("worktree_missing", "Worktree directory is missing")
            record.update(integrated=True, validated=True, worker_stopped=True)
            await owned_io(self.save, record)
            return record

    def inclusion_status(self, branch):
        _, status = self.git(["merge-base", "--is-ancestor", branch, "HEAD"], allowed=(0, 1))
        return status

    async def cleanup(self, identifier, *, active=False):
        async with self.lock:
            return await owned_io(self.cleanup_sync, identifier, active)

    def cleanup_sync(self, identifier, active):
        self.lease.acquire()
        try:
            record = self.inspect_sync(identifier)
            reasons = []
            if active:
                reasons.append("active_task")
            for flag in ("integrated", "validated", "worker_stopped"):
                if not record.get(flag):
                    reasons.append(flag + "_unconfirmed")
            if record.get("dirty") is not False:
                reasons.append("dirty_or_missing")
            _, included = self.git(
                ["merge-base", "--is-ancestor", record["branch"], "HEAD"], allowed=(0, 1)
            )
            if included != 0:
                reasons.append("branch_not_included")
            if reasons:
                self.audit(identifier, "retained", reasons)
                return {"removed": False, "path": record["path"], "reasons": reasons}
            path = self.path(identifier)
            # Revalidate the absolute target immediately before Git removes it.
            if path.parent != self.directory:
                raise ToolError("worktree_path_escape", "Unsafe worktree removal target")
            self.git(["worktree", "remove", str(path)])
            record["status"] = "removed"
            branch_removed = False
            branch_error = ""
            try:
                self.git(["branch", "-d", record["branch"]])
                branch_removed = True
            except ToolError as error:
                branch_error = str(error)
                self.audit(identifier, "branch_retained", [branch_error])
            record["branch_removed"] = branch_removed
            record["branch_error"] = branch_error
            self.save(record)
            self.audit(identifier, "removed", [])
            return {
                "removed": True,
                "path": str(path),
                "reasons": [],
                "branch_removed": branch_removed,
                "branch_error": branch_error,
            }
        finally:
            self.lease.close()

    def audit(self, identifier, outcome, reasons):
        record = {
            "timestamp": datetime.now(UTC).isoformat(),
            "id": identifier,
            "outcome": outcome,
            "reasons": reasons,
        }
        with (self.directory / "cleanup-audit.jsonl").open("ab") as stream:
            stream.write((json_dumps(record) + "\n").encode())
            stream.flush()
            os.fsync(stream.fileno())

    async def scan(self, *, active_ids=(), now=None):
        now = now or datetime.now(UTC)
        marker = self.directory / "last-scan.json"
        if marker.exists():
            last = datetime.fromisoformat(json.loads(marker.read_bytes())["timestamp"])
            if now - last < timedelta(days=30):
                return {"due": False, "results": []}
        results = []
        for path in sorted(self.directory.glob("*.json")):
            if not re.fullmatch(r"[0-9a-f]{32}", path.stem):
                continue
            record = await owned_io(self.read, path.stem)
            if record["status"] == "removed":
                continue
            results.append(await self.cleanup(path.stem, active=path.stem in active_ids))
        await owned_io(atomic_write, marker, json_dumps({"timestamp": now.isoformat()}).encode())
        return {"due": True, "results": results}
