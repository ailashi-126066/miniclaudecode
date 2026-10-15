"""One atomic memory.json per configured scope, a LangGraph BaseStore and a change log."""

import copy
import json
import os
import re
import time
from contextlib import ExitStack, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import cast
from uuid import UUID

from langgraph.store.base import (
    BaseStore,
    GetOp,
    Item,
    ListNamespacesOp,
    PutOp,
    SearchItem,
    SearchOp,
)

from pycode.kernel import StorageError, ToolError
from pycode.kernel.adapters.file_lease import FileLease
from pycode.kernel.adapters.path_io import atomic_write, owned_io
from pycode.memory.domain.memory import (
    MemorySnapshot,
    check_memory_write,
    memory_text,
    normalize_value,
)

KEY = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
NAMESPACE_PART = re.compile(r"[A-Za-z0-9][A-Za-z0-9_:-]{0,127}")
TOKEN = re.compile(r"[a-z0-9_]{2,}|[\u3400-\u9fff]")
MISSING = object()


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON object key")
        result[key] = value
    return result


def encode(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False).encode()


def decode_memory_snapshot(payload):
    try:
        raw = json.loads(payload, object_pairs_hook=reject_duplicate_keys)
        if not isinstance(raw, dict) or type(raw.get("schema_version")) is not int:
            raise ValueError("Memory snapshot version is invalid")
        checked = MemorySnapshot.model_validate(raw)
    except (ValueError, TypeError, UnicodeError) as error:
        raise StorageError("memory_corrupt", "Memory JSON schema is invalid") from error
    # Keep the stored record text verbatim so before/after comparisons stay byte-stable.
    state = {
        "schema_version": 1,
        "namespace": list(checked.namespace),
        "revision": checked.revision,
        "records": raw.get("records", {}),
    }
    if checked.migration is not None:
        state["migration"] = raw["migration"]
    return state


@contextmanager
def memory_lease(root):
    path = root / "memory.lock"
    if path.is_symlink():
        raise StorageError("memory_path_invalid", "Memory lock cannot be a symlink")
    root.mkdir(parents=True, exist_ok=True)
    lease = FileLease(path)
    try:
        for attempt in range(50):
            try:
                lease.acquire()
                break
            except StorageError as error:
                if error.code != "session_busy" or attempt == 49:
                    raise
                time.sleep(0.01)
        yield
    finally:
        lease.close()


def tokens(text):
    return set(TOKEN.findall(text.lower()))


def matches(value, condition):
    """Equality filters over dotted value paths, as passed by LangMem search tools."""
    for path, expected in condition.items():
        current = value
        for part in str(path).split("."):
            current = current.get(part, MISSING) if isinstance(current, dict) else MISSING
        if current is MISSING or current != expected:
            return False
    return True


class JsonMemoryStore(BaseStore):
    """Writes go through the core write filter; `changes` records before/after when set."""

    supports_ttl = False

    def __init__(self, roots, *, changes=None, rejected=None):
        # Only configuration supplies disk paths; model namespaces and keys never become paths.
        self.roots = {self.check_namespace(ns): Path(root).resolve() for ns, root in roots.items()}
        if not self.roots or len(set(self.roots.values())) != len(self.roots):
            raise ValueError("Memory namespaces require distinct configured roots")
        self.changes = changes
        # When set, filtered writes are skipped and listed here instead of raising.
        self.rejected = rejected

    def recording(self, *, skip_rejected=False):
        """A view over the same files that records every change it writes."""
        return JsonMemoryStore(self.roots, changes=[], rejected=[] if skip_rejected else None)

    @staticmethod
    def check_namespace(namespace):
        if (
            not isinstance(namespace, tuple)
            or not namespace
            or any(not isinstance(p, str) or not NAMESPACE_PART.fullmatch(p) for p in namespace)
        ):
            raise ToolError("invalid_memory_namespace", "Memory namespace is invalid")
        return namespace

    def check_target(self, namespace, key):
        if not isinstance(namespace, tuple) or namespace not in self.roots:
            raise ToolError("memory_scope_denied", "Memory namespace is outside the allowed scopes")
        if not isinstance(key, str) or not KEY.fullmatch(key):
            raise ToolError("invalid_memory_id", "Memory key is invalid")

    def read(self, namespace):
        path = self.roots[namespace] / "memory.json"
        if path.is_symlink():
            raise StorageError("memory_path_invalid", "Memory content file cannot be a symlink")
        if not path.exists():
            return {"schema_version": 1, "namespace": list(namespace), "revision": 0, "records": {}}
        try:
            state = decode_memory_snapshot(path.read_bytes())
        except OSError as error:
            raise StorageError("memory_io_error", "Memory file could not be read") from error
        if tuple(state["namespace"]) != namespace:
            raise StorageError(
                "memory_scope_denied", "Memory file belongs to a different namespace"
            )
        return state

    @staticmethod
    def existing_key(records, key):
        """LangMem normalizes UUID IDs with hyphens; old entries can use UUID hex IDs."""
        if key in records:
            return key
        try:
            identity = UUID(key)
        except ValueError:
            return key
        for existing in records:
            try:
                if UUID(existing) == identity:
                    return existing
            except ValueError:
                continue
        return key

    def write(self, namespace, changes, *, restore=False):
        """Apply {key: value | None}; restore=True writes exact saved records for undo."""
        self.check_target(namespace, "write")
        root = self.roots[namespace]
        try:
            with memory_lease(root):
                state = self.read(namespace)
                applied = []
                for key, value in changes.items():
                    self.check_target(namespace, key)
                    key = self.existing_key(state["records"], key)
                    previous = state["records"].get(key)
                    if value is None:
                        current = None
                    elif restore:
                        current = copy.deepcopy(value)
                    else:
                        value = normalize_value(value)
                        if previous is not None and previous["value"] == value:
                            continue
                        count = len(state["records"]) + (previous is None)
                        try:
                            check_memory_write(value, count)
                        except ToolError as error:
                            if self.rejected is None:
                                raise
                            self.rejected.append(
                                {"namespace": list(namespace), "key": key, "reason": error.code}
                            )
                            continue
                        now = datetime.now(timezone.utc).isoformat()
                        current = {
                            "value": value,
                            "created_at": previous["created_at"] if previous else now,
                            "updated_at": now,
                        }
                    if current == previous:
                        continue
                    if current is None:
                        state["records"].pop(key)
                    else:
                        state["records"][key] = current
                    applied.append((key, previous, current))
                if not applied:
                    return []
                state["revision"] += 1
                MemorySnapshot.model_validate(state)
                atomic_write(root / "memory.json", encode(state))
        except OSError as error:
            raise StorageError("memory_io_error", "Memory file could not be written") from error
        if self.changes is not None:
            for key, before, after in applied:
                self.changes.append(
                    {"namespace": list(namespace), "key": key, "before": before, "after": after}
                )
        return applied

    async def restore(self, changes):
        """Compare and restore an undo batch while holding every affected scope's lease."""

        def restore():
            namespaces = sorted({tuple(change["namespace"]) for change in changes})
            for namespace in namespaces:
                self.check_target(namespace, "restore")
            with ExitStack() as leases:
                for root in sorted(self.roots[namespace] for namespace in namespaces):
                    leases.enter_context(memory_lease(root))
                states = {namespace: self.read(namespace) for namespace in namespaces}
                # Validate the whole batch before any write; other sessions use these leases too.
                for change in changes:
                    namespace, key = tuple(change["namespace"]), change["key"]
                    self.check_target(namespace, key)
                    if states[namespace]["records"].get(key) != change["after"]:
                        raise StorageError(
                            "memory_undo_conflict",
                            "Memory changed after the automatic update; edit it manually",
                        )
                for change in changes:
                    records = states[tuple(change["namespace"])]["records"]
                    if change["before"] is None:
                        records.pop(change["key"], None)
                    else:
                        records[change["key"]] = copy.deepcopy(change["before"])
                for state in states.values():
                    state["revision"] += 1
                    MemorySnapshot.model_validate(state)
                for namespace, state in states.items():
                    atomic_write(self.roots[namespace] / "memory.json", encode(state))
                    # Keep already published scopes auditable if a later scope has an I/O error.
                    if self.changes is not None:
                        self.changes.extend(
                            {**change, "before": change["after"], "after": change["before"]}
                            for change in changes
                            if tuple(change["namespace"]) == namespace
                        )

        try:
            await owned_io(restore)
        except OSError as error:
            raise StorageError("memory_io_error", "Memory undo could not be written") from error

    @staticmethod
    def item(namespace, key, record, *, score=MISSING):
        options = {
            "namespace": namespace,
            "key": key,
            "value": copy.deepcopy(record["value"]),
            "created_at": record["created_at"],
            "updated_at": record["updated_at"],
        }
        # Only search supplies this optional score; the sentinel selects a plain Item.
        return (
            Item(**options)
            if score is MISSING
            else SearchItem(**options, score=cast(float | None, score))
        )

    def search(self, op):
        if not isinstance(op.namespace_prefix, tuple):
            raise ToolError("invalid_memory_namespace", "Memory query prefix is invalid")
        if op.filter is not None and not isinstance(op.filter, dict):
            raise ToolError("invalid_memory_filter", "Memory filters must be objects")
        if type(op.limit) is not int or type(op.offset) is not int or op.limit < 0 or op.offset < 0:
            raise ToolError("memory_query_budget", "Memory pagination is invalid")
        namespaces = [
            ns for ns in sorted(self.roots) if ns[: len(op.namespace_prefix)] == op.namespace_prefix
        ]
        wanted = tokens(op.query) if isinstance(op.query, str) and op.query.strip() else None
        found = []
        for ns in namespaces:
            for key, record in self.read(ns)["records"].items():
                if op.filter and not matches(record["value"], op.filter):
                    continue
                score = None
                if wanted is not None:
                    # Simple text match: share of query tokens present in the entry text.
                    overlap = wanted & tokens(memory_text(record["value"]))
                    if not overlap:
                        continue
                    score = len(overlap) / len(wanted)
                found.append((ns, key, record, score))
        # Newest first, then (stable) highest score first when a query was given.
        found.sort(key=lambda item: item[2]["updated_at"], reverse=True)
        if wanted is not None:
            found.sort(key=lambda item: item[3], reverse=True)
        page = found[op.offset : op.offset + op.limit]
        return [self.item(ns, key, record, score=score) for ns, key, record, score in page]

    def namespaces(self, op):
        result = []
        for ns in sorted(self.roots):
            if not self.read(ns)["records"]:
                continue
            accepted = True
            for condition in op.match_conditions or ():
                path = tuple(condition.path)
                section = ns[: len(path)] if condition.match_type == "prefix" else ns[-len(path) :]
                accepted = (
                    accepted
                    and len(path) <= len(ns)
                    and all(p == "*" or p == s for p, s in zip(path, section, strict=False))
                )
            if accepted:
                result.append(ns[: op.max_depth] if op.max_depth else ns)
        return sorted(set(result))[op.offset : op.offset + op.limit]

    def dispatch(self, op):
        if isinstance(op, GetOp):
            self.check_target(op.namespace, op.key)
            records = self.read(op.namespace)["records"]
            key = self.existing_key(records, op.key)
            record = records.get(key)
            return self.item(op.namespace, key, record) if record else None
        if isinstance(op, PutOp):
            if op.ttl is not None:
                raise ToolError("memory_capability_unsupported", "Memory TTL is unsupported")
            self.write(op.namespace, {op.key: op.value})
            return None
        if isinstance(op, SearchOp):
            return self.search(op)
        if isinstance(op, ListNamespacesOp):
            return self.namespaces(op)
        raise TypeError("Unsupported memory store operation")

    def batch(self, ops):
        return [self.dispatch(op) for op in ops]

    async def abatch(self, ops):
        return await owned_io(self.batch, list(ops))

    async def snapshot(self, namespace):
        self.check_target(namespace, "snapshot")
        return await owned_io(self.read, namespace)

    async def apply(self, namespace, changes, *, restore=False):
        return await owned_io(lambda: self.write(namespace, changes, restore=restore))

    async def get_mode(self, namespace):
        def read():
            path = self.roots[namespace] / "settings.json"
            if not path.exists():
                return "auto"
            try:
                value = json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
                if not isinstance(value, dict) or value.get("mode") not in {
                    "auto",
                    "review",
                    "off",
                }:
                    raise ValueError("Memory settings are invalid")
                return value["mode"]
            except (OSError, ValueError, TypeError, UnicodeError) as error:
                raise StorageError(
                    "invalid_memory_mode", "Memory settings cannot be read"
                ) from error

        return await owned_io(read)

    async def set_mode(self, namespace, mode):
        def write():
            root = self.roots[namespace]
            with memory_lease(root):
                atomic_write(root / "settings.json", encode({"mode": mode}))

        await owned_io(write)


class MemoryChangeLog:
    """Append-only JSONL of memory changes; one object per line, partial tails are skipped."""

    def __init__(self, path):
        self.path = Path(path)

    def append_sync(self, record):
        if self.path.is_symlink():
            raise StorageError("memory_path_invalid", "Memory change log cannot be a symlink")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with memory_lease(self.path.parent):
            # Delimit a torn previous tail before appending so the next valid record survives.
            with self.path.open("a+b") as output:
                output.seek(0, os.SEEK_END)
                if output.tell():
                    output.seek(-1, os.SEEK_END)
                    if output.read(1) != b"\n":
                        output.write(b"\n")
                output.write(encode(record) + b"\n")
                output.flush()
                os.fsync(output.fileno())

    def read_sync(self):
        if not self.path.exists():
            return []
        records = []
        try:
            for line in self.path.read_bytes().splitlines():
                try:
                    value = json.loads(line)
                except ValueError:
                    continue
                if isinstance(value, dict):
                    records.append(value)
        except OSError as error:
            raise StorageError("memory_io_error", "Memory change log cannot be read") from error
        return records

    async def append(self, record):
        await owned_io(self.append_sync, record)

    async def read(self):
        return await owned_io(self.read_sync)
