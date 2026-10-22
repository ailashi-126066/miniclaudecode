"""Team worktrees: component-owned implementation."""

import asyncio
import hashlib
import json
import os
import re
import stat
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from pycode.kernel import ToolError, data_hash, json_dumps
from pycode.kernel.adapters.file_lease import FileLease
from pycode.kernel.adapters.path_io import atomic_write, owned_io


class WorktreeManager:
    def __init__(self, workspace, directory, *, snapshot_factory=None):
        self.workspace = Path(workspace).resolve()
        self.snapshot_factory = snapshot_factory
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = asyncio.Lock()
        self.lease = FileLease(self.directory / "worktrees.lock")

    def git(self, arguments, *, workspace=None, allowed=(0,), data=None, index=None):
        environment = {}
        for key, value in os.environ.items():
            if not key.startswith("GIT_"):
                environment[key] = value
        if index is not None:
            environment["GIT_INDEX_FILE"] = str(index)
        # Worktrees use the repository's ordinary clean/smudge and line-ending
        # rules. Snapshot object storage deliberately has a different policy.
        try:
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
        except subprocess.TimeoutExpired as error:
            raise ToolError(
                "worktree_git_timeout", "Git operation exceeded its time limit"
            ) from error
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
                if self.snapshot_factory is None:
                    raise ToolError(
                        "snapshot_factory_missing",
                        "Uncommitted starts require a configured snapshot factory",
                    )
                snapshots = self.snapshot_factory(
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
            content = snapshots.read_blob(version.object_id)
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

    def revision(self, name, *, workspace=None):
        value, _ = self.git(
            ["rev-parse", "--verify", "--end-of-options", name + "^{commit}"],
            workspace=workspace,
        )
        return value.decode().strip()

    def branch_name(self, *, workspace=None):
        branch, status = self.git(
            ["symbolic-ref", "--quiet", "--short", "HEAD"],
            workspace=workspace,
            allowed=(0, 1),
        )
        if status:
            raise ToolError("worktree_detached", "Team integration requires an attached branch")
        return branch.decode("utf-8", "surrogateescape").strip()

    def merge_head(self, *, workspace=None):
        head, status = self.git(
            ["rev-parse", "--verify", "--quiet", "MERGE_HEAD"],
            workspace=workspace,
            allowed=(0, 1),
        )
        return head.decode().strip() if status == 0 else None

    def changes_sync(self, identifier, limit):
        if type(limit) is not int or not 1 <= limit <= 50000:
            raise ToolError("invalid_diff_limit", "Diff limit must be between 1 and 50000")
        record = self.read(identifier)
        path = self.path(identifier)
        if not path.is_dir():
            raise ToolError("worktree_missing", "Worker worktree is missing")
        branch = self.branch_name(workspace=path)
        if branch != record["branch"]:
            raise ToolError("worktree_branch_changed", "Worker branch no longer matches its record")
        head = self.revision("HEAD", workspace=path)
        base = self.revision(record["base"], workspace=path)
        source_tree = self.candidate_tree(path)
        diff_args = ["--no-ext-diff", "--no-textconv", "--no-renames"]
        diff, _ = self.git(
            ["diff", *diff_args, "--binary", base, source_tree, "--"], workspace=path
        )
        staged, _ = self.git(
            ["diff", *diff_args, "--binary", "--cached", "HEAD", "--"], workspace=path
        )
        names, _ = self.git(
            ["diff", *diff_args, "--name-only", "-z", base, source_tree, "--"], workspace=path
        )
        diff_stat, _ = self.git(
            ["diff", *diff_args, "--stat", base, source_tree, "--"], workspace=path
        )
        others, _ = self.git(["ls-files", "--others", "--exclude-standard", "-z"], workspace=path)
        status, _ = self.git(["status", "--porcelain=v1", "-z"], workspace=path)
        fingerprint = hashlib.sha256()
        for value in (head.encode(), base.encode(), source_tree.encode(), diff, staged, status):
            fingerprint.update(len(value).to_bytes(8, "big"))
            fingerprint.update(value)
        untracked = []
        previews = []
        remaining = max(0, limit - len(diff.decode("utf-8", "replace")))
        for raw in sorted(name for name in others.split(b"\0") if name):
            name = os.fsdecode(raw)
            target = path / name
            # Do not follow a parent symlink into another directory during a review.
            if not target.parent.resolve().is_relative_to(path):
                raise ToolError("worktree_path_escape", "Untracked path escapes Worker worktree")
            mode = target.lstat().st_mode
            if not stat.S_ISREG(mode) and not stat.S_ISLNK(mode):
                raise ToolError(
                    "unsupported_worktree_file", "Review supports files and symlinks only"
                )
            digest = hashlib.sha256()
            preview = bytearray()
            if target.is_symlink():
                content = os.fsencode(os.readlink(target))
                digest.update(content)
                preview.extend(content[:remaining])
                size, mode = len(content), "symlink"
            else:
                size, mode = 0, oct(target.stat().st_mode & 0o777)
                with target.open("rb") as stream:
                    while chunk := stream.read(65536):
                        size += len(chunk)
                        digest.update(chunk)
                        preview.extend(chunk[: max(0, remaining - len(preview))])
            entry = {"path": name, "size": size, "sha256": digest.hexdigest(), "mode": mode}
            fingerprint.update(json_dumps(entry, sort_keys=True).encode("utf-8", "surrogateescape"))
            untracked.append(entry)
            text = "[binary file]" if b"\0" in preview else preview.decode("utf-8", "replace")
            previews.append("\nUntracked " + name + ":\n" + text)
            remaining = max(0, remaining - len(text))
        merge_head = self.merge_head(workspace=path)
        if (
            self.revision("HEAD", workspace=path) != head
            or self.candidate_tree(path) != source_tree
        ):
            raise ToolError("review_stale", "Worker changed during review; review it again")
        fingerprint.update((merge_head or "").encode())
        rendered = diff.decode("utf-8", "replace") + "".join(previews)
        return {
            "id": identifier,
            "path": str(path),
            "base": base,
            "head": head,
            "source_tree": source_tree,
            "branch": branch,
            "fingerprint": fingerprint.hexdigest(),
            "files": sorted(
                {os.fsdecode(n) for n in names.split(b"\0") if n}
                | {item["path"] for item in untracked}
            ),
            "untracked": untracked,
            "stat": diff_stat.decode("utf-8", "replace")[:limit],
            "diff": rendered[:limit],
            "diff_truncated": len(rendered) > limit
            or any(item["size"] > limit for item in untracked),
            "merge_head": merge_head,
        }

    def candidate_tree(self, path):
        """Build an immutable review tree using an owned index, never the user's index."""
        names, _ = self.git(
            ["ls-files", "--cached", "--others", "--exclude-standard", "-z"], workspace=path
        )
        for raw in filter(None, names.split(b"\0")):
            target = path / os.fsdecode(raw)
            if not target.parent.resolve().is_relative_to(path):
                raise ToolError("worktree_path_escape", "Untracked path escapes Worker worktree")
            if not target.exists() and not target.is_symlink():
                continue
            mode = target.lstat().st_mode
            if not stat.S_ISREG(mode) and not stat.S_ISLNK(mode):
                raise ToolError(
                    "unsupported_worktree_file", "Review supports files and symlinks only"
                )
        index = self.directory / ("review-" + uuid4().hex + ".index")
        try:
            self.git(["read-tree", "HEAD"], workspace=path, index=index)
            self.git(["add", "-A", "--", "."], workspace=path, index=index)
            tree, _ = self.git(["write-tree"], workspace=path, index=index)
            return tree.decode().strip()
        finally:
            index.unlink(missing_ok=True)
            index.with_name(index.name + ".lock").unlink(missing_ok=True)

    async def changes(self, identifier, limit=20000):
        async with self.lock:
            return await owned_io(self.changes_sync, identifier, limit)

    def target_state(self):
        head = self.revision("HEAD")
        branch = self.branch_name()
        status, _ = self.git(["status", "--porcelain=v1", "-z"])
        if self.merge_head() is not None:
            raise ToolError("merge_recovery_required", "Finish the existing workspace merge first")
        if status:
            raise ToolError("merge_target_dirty", "Integration requires a clean target workspace")
        return head, branch

    def merge_preview_sync(self, identifier, review_fingerprint, action):
        if action not in {"merge", "prepare_resolution"}:
            raise ToolError("invalid_merge_action", "Unknown integration action")
        source = self.changes_sync(identifier, 20000)
        if source["fingerprint"] != review_fingerprint:
            raise ToolError("review_stale", "Worker changed since review; review it again")
        target_head, target_branch = self.target_state()
        preview = {
            "id": identifier,
            "fingerprint": source["fingerprint"],
            "review_fingerprint": source["fingerprint"],
            "source_head": source["head"],
            "source_tree": source["source_tree"],
            "source_branch": source["branch"],
            "source_merge_head": source["merge_head"],
            "target_head": target_head,
            "target_branch": target_branch,
            "action": action,
            "files": source["files"],
            "hooks_disabled": True,
        }
        preview["preview_id"] = data_hash(preview)
        return preview

    async def merge_preview(self, identifier, review_fingerprint, action="merge"):
        async with self.lock:
            return await owned_io(self.merge_preview_sync, identifier, review_fingerprint, action)

    def conflict_paths(self, path):
        names, _ = self.git(["diff", "--name-only", "--diff-filter=U", "-z"], workspace=path)
        return [os.fsdecode(name) for name in names.split(b"\0") if name]

    def reject_conflict_markers(self, path):
        # The index remains unmerged until the reviewed resolution is staged here.
        for name in self.conflict_paths(path):
            target = path / name
            if not target.parent.resolve().is_relative_to(path):
                raise ToolError("worktree_path_escape", "Conflict path escapes Worker worktree")
            if target.is_symlink() or not target.exists():
                continue
            with target.open("rb") as stream:
                if any(re.match(rb"^(?:<{7}|={7}|>{7})(?:\s|$)", line) for line in stream):
                    raise ToolError(
                        "resolution_required", "Review and resolve all conflict markers"
                    )

    def identity_options(self, *, workspace=None):
        options = ["-c", "commit.gpgsign=false"]
        for key, fallback in (("user.name", "pycode agent"), ("user.email", "agent@pycode.local")):
            value, status = self.git(["config", "--get", key], workspace=workspace, allowed=(0, 1))
            if status or not value.strip():
                options.extend(["-c", key + "=" + fallback])
        return options

    def commit_all(self, record, expected_tree, expected_head, expected_merge_head):
        path = self.path(record["id"])
        if self.revision("HEAD", workspace=path) != expected_head:
            raise ToolError("merge_preview_stale", "Worker HEAD changed after approval")
        pending_head = self.merge_head(workspace=path)
        if pending_head != expected_merge_head:
            raise ToolError("merge_preview_stale", "Worker merge state changed after approval")
        if pending_head:
            self.reject_conflict_markers(path)
        self.git(["add", "-A", "--", "."], workspace=path)
        if self.conflict_paths(path):
            raise ToolError("resolution_required", "Worker still has unresolved index entries")
        tree, _ = self.git(["write-tree"], workspace=path)
        if tree.decode().strip() != expected_tree:
            raise ToolError("merge_preview_stale", "Staged Worker content differs from approval")
        _, different = self.git(
            ["diff", "--cached", "--quiet", "--"], workspace=path, allowed=(0, 1)
        )
        if different or self.merge_head(workspace=path):
            self.git(
                [
                    *self.identity_options(workspace=path),
                    "commit",
                    "-m",
                    "Integrate reviewed agent changes",
                ],
                workspace=path,
            )
        committed, _ = self.git(["rev-parse", "HEAD^{tree}"], workspace=path)
        if committed.decode().strip() != expected_tree:
            raise ToolError("merge_preview_stale", "Committed Worker content differs from approval")
        head = self.revision("HEAD", workspace=path)
        if self.branch_name(workspace=path) != record["branch"]:
            raise ToolError("merge_preview_stale", "Worker branch changed while committing")
        if head != expected_head:
            parents, _ = self.git(["rev-list", "--parents", "-n", "1", head], workspace=path)
            expected_parents = [expected_head] + ([pending_head] if pending_head else [])
            if parents.decode().split()[1:] != expected_parents:
                raise ToolError("merge_preview_stale", "Worker history changed while committing")
        return head

    def restore_target_after_conflict(self, head):
        if self.merge_head() is not None:
            self.git(["merge", "--abort"])
        current, _ = self.target_state()
        if current != head:
            raise ToolError("merge_recovery_required", "Target HEAD changed during merge recovery")

    def verify_team_result(self, preview, source_head, *, empty):
        head, branch = self.target_state()
        if branch != preview["target_branch"]:
            raise ToolError("merge_recovery_required", "Target branch changed during integration")
        if empty:
            valid_parent = head == preview["target_head"]
        else:
            parents, _ = self.git(["rev-list", "--parents", "-n", "1", head])
            values = parents.decode().split()
            valid_parent = len(values) == 3 and values[1:] == [preview["target_head"], source_head]
        _, included = self.git(["merge-base", "--is-ancestor", source_head, head], allowed=(0, 1))
        if not valid_parent or included:
            raise ToolError("merge_recovery_required", "Actual integration differs from approval")
        return head

    def recover_timeout(self, record, preview):
        record["merge_recovery_required"] = True
        self.save(record)
        try:
            path = self.path(record["id"])
            source_merge_head = self.merge_head(workspace=path)
            record["timeout_source_state"] = {
                "head": self.revision("HEAD", workspace=path),
                "branch": self.branch_name(workspace=path),
                "merge_head": source_merge_head,
            }
            if source_merge_head:
                record["resolution_pending"] = {
                    "resolution_pending": True,
                    "path": str(path),
                    "conflicts": self.conflict_paths(path),
                    "target_head": source_merge_head,
                }
            if source_merge_head or preview["action"] == "prepare_resolution":
                record["source_recovery_required"] = True
                self.save(record)
                raise ToolError(
                    "merge_recovery_required", "Inspect the retained Worker after Git timed out"
                )
            if (
                self.branch_name() == preview["target_branch"]
                and self.revision("HEAD") == preview["target_head"]
            ):
                self.restore_target_after_conflict(preview["target_head"])
                record["merge_recovery_required"] = False
                record["timeout_target_restored"] = True
                self.save(record)
                return
        except ToolError:
            pass
        raise ToolError(
            "merge_recovery_required", "Git timed out; inspect the retained source and target"
        )

    def merge_into_workspace_sync(self, identifier, preview, action):
        self.lease.acquire()
        record = None
        try:
            if not isinstance(preview, dict) or preview.get("action") != action:
                raise ToolError(
                    "merge_preview_stale", "Integration needs a matching approval preview"
                )
            current = self.merge_preview_sync(identifier, preview.get("fingerprint"), action)
            if current != preview:
                raise ToolError("merge_preview_stale", "Source or target changed after approval")
            record = self.read(identifier)
            path = self.path(identifier)
            record["merge_intent"] = preview
            self.save(record)
            if action == "prepare_resolution":
                if self.merge_head(workspace=path):
                    raise ToolError("resolution_pending", "Worker already contains a pending merge")
                # Preserve the reviewed changes as a commit before preparing the conflict.
                self.commit_all(
                    record,
                    preview["source_tree"],
                    preview["source_head"],
                    preview["source_merge_head"],
                )
                _, code = self.git(
                    [
                        *self.identity_options(workspace=path),
                        "merge",
                        "--no-ff",
                        "--no-commit",
                        preview["target_head"],
                    ],
                    workspace=path,
                    allowed=(0, 1),
                )
                conflicts = self.conflict_paths(path)
                if code and not conflicts:
                    raise ToolError("merge_failed", "Git could not prepare the Worker resolution")
                result = {
                    "merged": False,
                    "resolution_pending": True,
                    "conflicts": conflicts,
                    "path": str(path),
                    "target_head": preview["target_head"],
                }
                record["resolution_pending"] = result
                self.save(record)
                return result
            source_head = self.commit_all(
                record, preview["source_tree"], preview["source_head"], preview["source_merge_head"]
            )
            # A failed/changed external Git operation must not integrate into another branch.
            if self.target_state() != (preview["target_head"], preview["target_branch"]):
                raise ToolError("merge_preview_stale", "Target changed while committing Worker")
            _, included = self.git(
                ["merge-base", "--is-ancestor", source_head, "HEAD"], allowed=(0, 1)
            )
            if included:
                _, code = self.git(
                    [
                        *self.identity_options(),
                        "merge",
                        "--no-ff",
                        "--no-edit",
                        source_head,
                    ],
                    allowed=(0, 1),
                )
                if code:
                    conflicts = self.conflict_paths(self.workspace)
                    try:
                        self.restore_target_after_conflict(preview["target_head"])
                    except ToolError as error:
                        record["merge_recovery_required"] = True
                        self.save(record)
                        raise ToolError(
                            "merge_recovery_required",
                            "Merge recovery failed; inspect the target repository",
                        ) from error
                    record["merge_conflicts"] = conflicts
                    self.save(record)
                    return {
                        "merged": False,
                        "conflicts": conflicts,
                        "error_code": "merge_conflict" if conflicts else "merge_failed",
                        "target_restored": True,
                        "worktree_retained": True,
                    }
            try:
                merge_commit = self.verify_team_result(preview, source_head, empty=included == 0)
            except ToolError:
                record["merge_recovery_required"] = True
                self.save(record)
                raise
            result = {
                "merged": True,
                "empty": included == 0,
                "merge_commit": merge_commit,
                "source_head": source_head,
                "preview_id": preview["preview_id"],
                "worktree_retained": True,
            }
            record["last_team_merge"] = result
            record.pop("resolution_pending", None)
            self.save(record)
            return result
        except ToolError as error:
            if error.code == "worktree_git_timeout" and record is not None:
                self.recover_timeout(record, preview)
            raise
        finally:
            self.lease.close()

    async def merge_into_workspace(self, identifier, preview, action="merge"):
        async with self.lock:
            return await owned_io(self.merge_into_workspace_sync, identifier, preview, action)

    def complete_team_merge_sync(self, identifier, merge_commit):
        self.lease.acquire()
        try:
            record = self.read(identifier)
            receipt = record.get("last_team_merge", {})
            if not receipt.get("merged") or receipt.get("merge_commit") != merge_commit:
                raise ToolError("integration_unconfirmed", "No matching successful team merge")
            for revision in (merge_commit, receipt["source_head"]):
                _, status = self.git(
                    ["merge-base", "--is-ancestor", revision, "HEAD"], allowed=(0, 1)
                )
                if status:
                    raise ToolError(
                        "integration_unconfirmed", "Integration is not in the current target"
                    )
            record.update(integrated=True, integration_verified=True, worker_stopped=True)
            self.save(record)
        finally:
            self.lease.close()
        return self.cleanup_sync(identifier, False)

    async def complete_team_merge(self, identifier, merge_commit):
        """Call only after the team durably records success and closes the child session."""
        async with self.lock:
            return await owned_io(self.complete_team_merge_sync, identifier, merge_commit)

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
            for flag in ("integrated", "worker_stopped"):
                if not record.get(flag):
                    reasons.append(flag + "_unconfirmed")
            if not record.get("validated") and not record.get("integration_verified"):
                reasons.append("validated_unconfirmed")
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
