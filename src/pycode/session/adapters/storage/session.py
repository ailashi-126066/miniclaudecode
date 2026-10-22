# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/session-manager.ts
# Adaptation: native schema, fsync, atomic position, writer lease and corruption checks.
"""Append-only JSONL history with a separately durable active branch position."""

import asyncio
import json
import os
import shutil
from datetime import datetime, timezone
from pathlib import Path
from types import MappingProxyType
from uuid import uuid4

from pycode.context import valid_kept_boundary
from pycode.kernel import (
    EntryDraft,
    Message,
    SessionEntry,
    SessionHeader,
    SessionSnapshot,
    SessionTree,
    StorageError,
    Timing,
    clone_value,
    freeze_value,
)
from pycode.kernel.adapters.file_lease import FileLease
from pycode.kernel.adapters.path_io import atomic_write, owned_io
from pycode.session.adapters.storage.session_codec import (
    decode_entry,
    decode_header,
    encode_entry,
    encode_header,
)
from pycode.session.domain.execution_recovery import run_belongs_to_session


class JsonlSessionStore:
    def __init__(self, directory: Path, header: SessionHeader, timing: Timing) -> None:
        self._directory = directory
        self._log = directory / "entries.jsonl"
        self._position = directory / "position.json"
        self._header = header
        self._timing = timing
        self._entries_by_id: dict[str, SessionEntry] = {}
        self._active_path_ids: list[str] = []
        self._leaf = None
        self._revision = 0
        self._offset = 0
        self._issues = []
        self._loaded = False
        self._persisted = False
        self._closed = False
        self._fingerprint = None
        self._position_fingerprint = None
        self._lease = FileLease(directory / "writer.lock")
        self._lock = asyncio.Lock()

    async def run_io(self, operation):
        async with self._lock:
            if self._closed:
                raise StorageError("session_closed", "Session store is closed")
            try:
                return await owned_io(operation)
            except (OSError, ValueError, TypeError, KeyError) as error:
                raise StorageError(
                    "session_storage_error", "Session storage operation failed"
                ) from error

    def load(self) -> None:
        if self._loaded:
            if self._persisted:
                current = self._log.stat()
                if self._fingerprint != (current.st_mtime_ns, current.st_size):
                    raise StorageError(
                        "session_changed_externally", "Session history changed outside its writer"
                    )
            return
        if not self._log.exists():
            self._loaded = True
            return
        entries = []
        physical = []
        index = {}
        offset = 0
        issues = []
        with self._log.open("rb") as source:
            header_line = source.readline()
            header = decode_header(header_line)
            if (
                header.session_id != self._header.session_id
                or header.workspace != self._header.workspace
            ):
                raise StorageError(
                    "session_identity_conflict",
                    "Stored session belongs to a different identity or workspace",
                )
            offset = source.tell()
            for line in source:
                end = source.tell()
                if not line.strip():
                    offset = end
                    continue
                try:
                    entry = freeze_value(decode_entry(line))
                except StorageError:
                    if not line.endswith(b"\n"):
                        issues.append("partial_tail")
                        break
                    raise
                if entry.id in index:
                    if index[entry.id] != entry:
                        raise StorageError(
                            "duplicate_entry_conflict", "Duplicate entry ID has different content"
                        )
                    issues.append("duplicate_entry")
                else:
                    if entry.parent_id is not None and entry.parent_id not in index:
                        raise StorageError(
                            "broken_session_parent", "Session parent chain is broken"
                        )
                    index[entry.id] = entry
                    entries.append(entry)
                    physical.append((end, entry))
                offset = end
                if not line.endswith(b"\n"):
                    issues.append("missing_newline")
        leaf = entries[-1].id if entries else None
        revision = len(entries)
        if self._position.exists():
            position = json.loads(self._position.read_bytes())
            if (
                not isinstance(position, dict)
                or set(position)
                != {"schema_version", "session_id", "leaf_id", "revision", "log_offset"}
                or type(position["schema_version"]) is not int
                or position["schema_version"] != 1
                or position["session_id"] != header.session_id
            ):
                raise StorageError("invalid_session_position", "Session position is incompatible")
            leaf = position["leaf_id"]
            revision = position["revision"]
            recorded_offset = position["log_offset"]
            if recorded_offset > offset or (leaf is not None and leaf not in index):
                raise StorageError(
                    "invalid_session_position", "Session position refers to missing history"
                )
            for end, entry in physical:
                if end > recorded_offset:
                    if entry.parent_id != leaf:
                        raise StorageError(
                            "uncertain_session_position",
                            "Unpublished history does not extend the recorded branch",
                        )
                    leaf = entry.id
                    revision += 1
                    issues.append("position_after_append_gap")
        tree = SessionTree(index)
        for entry in entries:
            if entry.type in {"context_edit", "compaction"}:
                self.validate_branch_entry(entry, tree.path(entry.parent_id))
        self._header = header
        self._entries_by_id = index
        self._active_path_ids = [entry.id for entry in tree.path(leaf)]
        self._leaf = leaf
        self._revision = revision
        self._offset = offset
        self._issues = issues
        self._loaded = True
        self._persisted = True
        current = self._log.stat()
        self._fingerprint = (current.st_mtime_ns, current.st_size)
        if self._position.exists():
            position_stat = self._position.stat()
            self._position_fingerprint = (position_stat.st_mtime_ns, position_stat.st_size)

    async def snapshot(self) -> SessionSnapshot:
        def read():
            self.load()
            return SessionSnapshot(
                self._header,
                tuple(self._entries_by_id.values()),
                self._leaf,
                self._revision,
                self._offset,
                tuple(self._issues),
                MappingProxyType(self._entries_by_id),
                tuple(self._active_path_ids),
            )

        return await self.run_io(read)

    def acquire_writer(self) -> None:
        acquired = self._lease.acquire()
        if acquired and self._log.exists():
            current = self._log.stat()
            position = self._position.stat() if self._position.exists() else None
            position_fingerprint = (
                (position.st_mtime_ns, position.st_size) if position is not None else None
            )
            if (
                self._fingerprint != (current.st_mtime_ns, current.st_size)
                or self._position_fingerprint != position_fingerprint
            ):
                self._loaded = False
        self.load()

    async def claim_writer(self) -> None:
        """Take the native lease before classifying any prior executor as lost."""
        await self.run_io(self.acquire_writer)

    async def append_message(self, message: Message) -> str:
        run_id = message.usage.run_id if message.usage is not None else None
        operation_id = None
        if message.tool_result is not None:
            run_id = message.tool_result.run_id
            operation_id = message.tool_result.operation_id
        entry = await self.append_entry(
            EntryDraft("message", message=message, run_id=run_id, operation_id=operation_id)
        )
        return entry.id

    async def append_entry(self, draft: EntryDraft) -> SessionEntry:
        def append():
            self.load()
            self.acquire_writer()
            if draft.type == "input_queued":
                queued_message = draft.message
                if queued_message is None:
                    raise StorageError("invalid_session_entry", "Queued input requires its message")
                for previous in self._entries_by_id.values():
                    if previous.type not in {"input_queued", "message"}:
                        continue
                    if previous.require_message().id != queued_message.id:
                        continue
                    if previous.message != draft.message or (
                        previous.type == "input_queued"
                        and (previous.run_id != draft.run_id or previous.data != draft.data)
                    ):
                        raise StorageError("input_id_conflict", "Queued input identity changed")
                    active = SessionTree(self._entries_by_id).path(self._leaf)
                    if previous.id not in {entry.id for entry in active}:
                        raise StorageError(
                            "input_branch_conflict", "Input belongs to another branch"
                        )
                    return previous
            if draft.type == "message":
                if draft.message is None:
                    raise StorageError(
                        "invalid_session_entry", "Message entry requires its completed message"
                    )
                for previous in self._entries_by_id.values():
                    if (
                        previous.type != "message"
                        or previous.require_message().id != draft.message.id
                    ):
                        continue
                    if previous.message != draft.message:
                        raise StorageError(
                            "input_id_conflict", "Message ID already has different content"
                        )
                    if previous.id not in {
                        entry.id for entry in SessionTree(self._entries_by_id).path(self._leaf)
                    }:
                        raise StorageError(
                            "input_branch_conflict", "Message ID belongs to another branch"
                        )
                    return previous
            identifier = draft.id or uuid4().hex
            data = clone_value(draft.data)
            if draft.type == "compaction" and data.get("first_kept_entry_id") is None:
                data["first_kept_entry_id"] = identifier
            entry = SessionEntry(
                identifier,
                self._leaf,
                draft.type,
                datetime.fromtimestamp(self._timing.now(), timezone.utc).isoformat(),
                data,
                clone_value(draft.message),
                clone_value(draft.selection),
                draft.run_id,
                draft.operation_id,
            )
            entry = freeze_value(decode_entry(encode_entry(entry)))
            for previous in self._entries_by_id.values():
                if previous.id == entry.id:
                    raise StorageError("entry_id_conflict", "Entry ID already exists")
            self.validate_branch_entry(entry)
            will_persist = (
                self._persisted
                or entry.type == "hook_call"
                or (entry.type == "message" and entry.require_message().role == "user")
            )
            if will_persist:
                self.repair_log()
                self.write_entry(entry)
            self._entries_by_id[entry.id] = entry
            self._active_path_ids.append(entry.id)
            self._leaf = entry.id
            self._revision += 1
            if will_persist:
                self.publish_position()
            return entry

        return await self.run_io(append)

    def validate_branch_entry(self, entry: SessionEntry, path=None) -> None:
        if path is None:
            path = SessionTree(self._entries_by_id).path(self._leaf)
        active = {item.id: item for item in path}
        if entry.type == "input_queued":
            started = any(
                item.type == "custom"
                and item.data.get("kind") == "run_start"
                and item.run_id == entry.run_id
                and run_belongs_to_session(self._header, item)
                for item in path
            )
            ended = any(
                item.type == "custom"
                and item.data.get("kind") == "run_end"
                and item.run_id == entry.run_id
                for item in self._entries_by_id.values()
            )
            if not started or ended:
                raise StorageError("run_settled", "Queued input has no active owning run")
        if entry.type == "context_edit":
            target = active.get(entry.data["target_id"])
            if target is None or (
                target.type != "custom_message"
                and not (target.type == "message" and target.require_message().role != "system")
            ):
                raise StorageError(
                    "invalid_context_target", "Context target is not editable on the active branch"
                )
        if entry.type == "compaction":
            kept = entry.data["first_kept_entry_id"]
            if kept != entry.id and kept not in active:
                raise StorageError(
                    "invalid_compaction_boundary", "Retained boundary is not on the active branch"
                )
            if not valid_kept_boundary(path, None if kept == entry.id else kept):
                raise StorageError(
                    "unpaired_compaction_boundary",
                    "Compaction would split a tool call from its result",
                )

    def write_entry(self, entry: SessionEntry) -> None:
        self._directory.mkdir(parents=True, exist_ok=True)
        if not self._persisted:
            temporary = self._log.with_name(f"entries.{uuid4().hex}.partial")
            try:
                with temporary.open("xb") as output:
                    output.write(encode_header(self._header))
                    for pending in self._entries_by_id.values():
                        output.write(encode_entry(pending))
                    output.write(encode_entry(entry))
                    output.flush()
                    os.fsync(output.fileno())
                if self._log.exists():
                    raise StorageError("session_created_externally", "Session file already exists")
                os.replace(temporary, self._log)
            finally:
                temporary.unlink(missing_ok=True)
            self._persisted = True
        else:
            with self._log.open("ab") as output:
                output.write(encode_entry(entry))
                output.flush()
                os.fsync(output.fileno())
        self._offset = self._log.stat().st_size
        current = self._log.stat()
        self._fingerprint = (current.st_mtime_ns, current.st_size)

    def publish_position(self) -> None:
        data = {
            "schema_version": 1,
            "session_id": self._header.session_id,
            "leaf_id": self._leaf,
            "revision": self._revision,
            "log_offset": self._offset,
        }
        atomic_write(self._position, json.dumps(data, sort_keys=True).encode("utf-8"))
        current = self._position.stat()
        self._position_fingerprint = (current.st_mtime_ns, current.st_size)

    def repair_log(self) -> None:
        if not self._persisted:
            return
        needs_repair = any(issue in self._issues for issue in ("partial_tail", "missing_newline"))
        if not needs_repair:
            return
        backup = self._directory / "recovery" / f"entries.{uuid4().hex}.jsonl"
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self._log, backup)
        temporary = self._log.with_name(f"entries.{uuid4().hex}.partial")
        try:
            with self._log.open("rb") as source, temporary.open("wb") as output:
                header_line = source.readline()
                output.write(header_line)
                remaining = self._offset - len(header_line)
                while remaining:
                    data = source.read(min(remaining, 1024 * 1024))
                    if not data:
                        raise StorageError(
                            "session_changed_during_repair", "Session log changed during repair"
                        )
                    output.write(data)
                    remaining -= len(data)
                if "missing_newline" in self._issues:
                    output.write(b"\n")
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self._log)
            self._offset = self._log.stat().st_size
            current = self._log.stat()
            self._fingerprint = (current.st_mtime_ns, current.st_size)
            self._issues = [
                issue for issue in self._issues if issue not in {"partial_tail", "missing_newline"}
            ]
        finally:
            temporary.unlink(missing_ok=True)

    async def branch(self, leaf_id: str | None) -> None:
        def change():
            self.load()
            self.acquire_writer()
            path = SessionTree(self._entries_by_id).path(leaf_id)
            self._active_path_ids = [entry.id for entry in path]
            self._leaf = leaf_id
            self._revision += 1
            if self._persisted:
                self.publish_position()

        await self.run_io(change)

    async def aclose(self) -> None:
        if self._closed:
            return
        await self.run_io(self._lease.close)
        self._closed = True

    async def import_path(self, entries: tuple[SessionEntry, ...], leaf_id: str | None) -> None:
        def publish():
            self.load()
            self.acquire_writer()
            if self._persisted or self._entries_by_id:
                raise StorageError("fork_target_exists", "Fork target already contains history")
            SessionTree(entries).path(leaf_id)
            self._directory.mkdir(parents=True, exist_ok=True)
            temporary = self._log.with_name(f"fork.{uuid4().hex}.partial")
            try:
                with temporary.open("xb") as output:
                    output.write(encode_header(self._header))
                    for entry in entries:
                        output.write(encode_entry(entry))
                    output.flush()
                    os.fsync(output.fileno())
                if self._log.exists():
                    raise StorageError("fork_target_exists", "Fork target already contains history")
                os.replace(temporary, self._log)
            finally:
                temporary.unlink(missing_ok=True)
            self._entries_by_id = {}
            for entry in entries:
                self._entries_by_id[entry.id] = freeze_value(entry)
            self._active_path_ids = [
                entry.id for entry in SessionTree(self._entries_by_id).path(leaf_id)
            ]
            self._leaf = leaf_id
            self._revision = len(entries)
            self._persisted = True
            current = self._log.stat()
            self._offset = current.st_size
            self._fingerprint = (current.st_mtime_ns, current.st_size)
            self.publish_position()

        await self.run_io(publish)
