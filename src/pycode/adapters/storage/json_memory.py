"""One atomic JSON content file per trusted namespace, including staged update views."""

import asyncio
import copy
import hashlib
import json
import re
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from langgraph.store.base import (
    BaseStore,
    GetOp,
    Item,
    ListNamespacesOp,
    PutOp,
    SearchItem,
    SearchOp,
)

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.storage.path_io import atomic_write, owned_io, safe_relative
from pycode.core.errors import StorageError, ToolError
from pycode.core.hashing import data_hash
from pycode.core.memory_evidence import PreparedMemoryUpdate
from pycode.core.memory_schema import (
    MemoryGroupReceipt,
    MemorySnapshot,
    MemoryToolReceipt,
    SourceBatch,
    StoredMemory,
    resolve_memory_key,
    validate_memory_value,
)
from pycode.core.token_estimate import estimate_text

OPERATORS = {"$eq", "$ne", "$gt", "$gte", "$lt", "$lte"}
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
        required = {"schema_version", "namespace", "revision", "records", "processed_batches"}
        if (
            not isinstance(raw, dict)
            or not required <= raw.keys()
            or not raw.keys()
            <= required | {"migration", "group_receipts", "tool_receipts", "move_receipts"}
            or type(raw.get("schema_version")) is not int
        ):
            raise ValueError("Memory snapshot fields or version are invalid")
        return MemorySnapshot.model_validate_json(encode(raw))
    except (ValueError, TypeError, UnicodeError) as error:
        raise StorageError(
            "memory_corrupt", "Memory JSON schema or integrity is invalid"
        ) from error


def normalize_value(value):
    # The official manage tool writes {content: schema}; Store Manager writes schema directly.
    if isinstance(value, dict) and set(value) == {"content"}:
        value = value["content"]
    return validate_memory_value(value)


def validate_filter(value):
    if not isinstance(value, dict):
        raise ToolError("invalid_memory_filter", "Memory filters must be objects")
    for key, item in value.items():
        if not isinstance(key, str) or not key or any(not part for part in key.split(".")):
            raise ToolError("invalid_memory_filter", "Memory filter paths must be nonempty")
        if isinstance(item, dict):
            operators = {name for name in item if name.startswith("$")}
            if operators:
                if operators != set(item) or not operators <= OPERATORS:
                    raise ToolError(
                        "invalid_memory_filter", "Memory filter operator is unsupported"
                    )
            else:
                validate_filter(item)


def matches(value, condition):
    if isinstance(condition, dict):
        if any(key.startswith("$") for key in condition):
            if value is MISSING:
                return False
            for operator, expected in condition.items():
                if operator == "$eq":
                    result = value == expected
                elif operator == "$ne":
                    result = value != expected
                else:
                    try:
                        if operator == "$gt":
                            result = value > expected
                        elif operator == "$gte":
                            result = value >= expected
                        elif operator == "$lt":
                            result = value < expected
                        else:
                            result = value <= expected
                    except TypeError:
                        result = False
                if not result:
                    return False
            return True
        if not isinstance(value, dict):
            return False
        for path, expected in condition.items():
            current = value
            for part in path.split("."):
                current = current.get(part, MISSING) if isinstance(current, dict) else MISSING
            if not matches(current, expected):
                return False
        return True
    return value is not MISSING and value == condition


@contextmanager
def memory_lease(root):
    for path in (root / "memory.lock", root / "versions"):
        if path.is_symlink():
            raise StorageError("memory_path_invalid", "Memory internal path cannot be a symlink")
    lease = FileLease(root / "memory.lock")
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


class JsonMemoryStore(BaseStore):
    supports_ttl = False

    def __init__(self, roots, *, selector, validate_change, directory_tokens=6000, max_queries=16):
        # Only configuration supplies disk paths; model namespaces and keys never become paths.
        self.roots = {self.check_namespace(ns): Path(root).resolve() for ns, root in roots.items()}
        if not self.roots or len(set(self.roots.values())) != len(self.roots):
            raise ValueError("Memory namespaces require distinct configured roots")
        if directory_tokens < 128 or max_queries < 1:
            raise ValueError("Memory query budgets must be positive and usable")
        self.selector = selector
        self.validate_change = validate_change
        self.directory_tokens = directory_tokens
        self.max_queries = max_queries

    @staticmethod
    def check_namespace(namespace):
        if (
            not isinstance(namespace, tuple)
            or not namespace
            or any(
                not isinstance(part, str)
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_:-]{0,127}", part)
                for part in namespace
            )
        ):
            raise ToolError("invalid_memory_namespace", "Memory namespace is invalid")
        return namespace

    def check_target(self, namespace, key):
        if not isinstance(namespace, tuple) or namespace not in self.roots:
            raise ToolError("memory_scope_denied", "Memory namespace is outside the allowed scopes")
        if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", key):
            raise ToolError("invalid_memory_id", "Memory key is invalid")

    def read(self, namespace):
        path = self.roots[namespace] / "memory.json"
        if path.is_symlink():
            raise StorageError("memory_path_invalid", "Memory content file cannot be a symlink")
        if not path.exists():
            return MemorySnapshot(namespace=namespace).model_dump(mode="json")
        try:
            state = decode_memory_snapshot(path.read_bytes())
        except OSError as error:
            raise StorageError("memory_io_error", "Memory file could not be read") from error
        if state.namespace != namespace:
            raise StorageError(
                "memory_scope_denied", "Memory file belongs to a different namespace"
            )
        return state.model_dump(mode="json")

    def publish(self, namespace, state, previous):
        """Called only while holding the scope lease, for normal commits and migration."""
        root = self.roots[namespace]
        MemorySnapshot.model_validate_json(encode(state))
        if state["namespace"] != list(namespace):
            raise StorageError(
                "memory_scope_denied", "Published snapshot has a different namespace"
            )
        if (root / "memory.json").exists():
            if previous is None:
                raise StorageError(
                    "memory_migration_conflict", "Memory already has active JSON content"
                )
            backup = root / "versions" / f"revision-{previous['revision']}.json"
            if backup.is_symlink():
                raise StorageError("memory_path_invalid", "Memory backup cannot be a symlink")
            if backup.exists() and backup.read_bytes() != encode(previous):
                raise StorageError("memory_backup_conflict", "Memory version backup differs")
            if not backup.exists():
                atomic_write(backup, encode(previous))
        atomic_write(root / "memory.json", encode(state))

    def write_changes(
        self,
        namespace,
        changes,
        *,
        revision=None,
        batches=None,
        group_receipts=None,
        tool_receipt=None,
    ):
        root = self.roots[namespace]
        try:
            with memory_lease(root):
                state = self.read(namespace)
                old = copy.deepcopy(state)
                if tool_receipt is not None:
                    identity, metadata = tool_receipt
                    prior = state["tool_receipts"].get(identity)
                    if prior is not None:
                        expected = {
                            key: value
                            for key, value in prior.items()
                            if key not in {"revision", "record_versions"}
                        }
                        if expected != metadata:
                            raise StorageError(
                                "memory_tool_conflict", "Committed tool identity changed"
                            )
                        return state
                if batches:
                    if len(batches) != 1:
                        raise ValueError("Commit exactly one source batch per scope")
                    for batch_id, batch in batches.items():
                        prior = state["processed_batches"].get(batch_id)
                        if prior is not None:
                            if prior != batch:
                                raise StorageError(
                                    "memory_batch_conflict", "Source batch identity changed"
                                )
                            # A committed batch must never replay edits after a later correction.
                            return state
                for identity, receipt in (group_receipts or {}).items():
                    checked = MemoryGroupReceipt.model_validate_json(encode(receipt)).model_dump(
                        mode="json"
                    )
                    prior = state["group_receipts"].get(identity)
                    if prior is not None and prior != checked:
                        raise StorageError(
                            "memory_group_conflict", "Committed group identity changed"
                        )
                if revision is not None and state["revision"] != revision:
                    raise StorageError(
                        "memory_revision_conflict", "Memory changed; reread before merging"
                    )
                for key, value in changes.items():
                    self.check_target(namespace, key)
                    key = resolve_memory_key(state["records"], key)
                    previous = state["records"].get(key)
                    value = normalize_value(value) if value is not None else None
                    if (
                        previous is not None
                        and value is not None
                        and previous["value"]["kind"] != value["kind"]
                    ):
                        raise ToolError(
                            "memory_kind_conflict",
                            "Memory kind changes require an explicit migration",
                        )
                    self.validate_change(
                        namespace, key, copy.deepcopy(value), copy.deepcopy(previous)
                    )
                    if value is None:
                        state["records"].pop(key, None)
                    else:
                        if previous is not None and previous["value"] == value:
                            continue
                        now = datetime.now(timezone.utc).isoformat()
                        state["records"][key] = {
                            "value": value,
                            "created_at": previous["created_at"] if previous else now,
                            "updated_at": now,
                        }
                state["processed_batches"].update(batches or {})
                state["group_receipts"].update(group_receipts or {})
                if tool_receipt is not None:
                    identity, metadata = tool_receipt
                    versions = {
                        key: data_hash(
                            StoredMemory.model_validate_json(
                                encode(state["records"][key])
                            ).model_dump(mode="json")
                        )
                        if key in state["records"]
                        else None
                        for key in changes
                    }
                    checked = MemoryToolReceipt.model_validate_json(
                        encode(
                            {
                                **metadata,
                                "revision": state["revision"] + 1,
                                "record_versions": versions,
                            }
                        )
                    )
                    state["tool_receipts"][identity] = checked.model_dump(mode="json")
                if state == old:
                    return state
                state["revision"] += 1
                self.publish(namespace, state, old)
                return state
        except OSError as error:
            raise StorageError(
                "memory_io_error", "Memory snapshot could not be published"
            ) from error

    @staticmethod
    def item(namespace, key, record, *, score=MISSING):
        options = {
            "namespace": namespace,
            "key": key,
            "value": copy.deepcopy(record["value"]),
            "created_at": record["created_at"],
            "updated_at": record["updated_at"],
        }
        return Item(**options) if score is MISSING else SearchItem(**options, score=score)

    @staticmethod
    def pagination(limit, offset, *, maximum=100):
        if (
            type(limit) is not int
            or type(offset) is not int
            or not 0 <= limit <= maximum
            or not 0 <= offset <= 10000
        ):
            raise ToolError("memory_query_budget", "Memory pagination exceeds its bounds")

    def search_records(self, op):
        self.pagination(op.limit, op.offset)
        if not isinstance(op.namespace_prefix, tuple) or any(
            not isinstance(p, str) for p in op.namespace_prefix
        ):
            raise ToolError("invalid_memory_namespace", "Memory query prefix is invalid")
        if op.filter is not None:
            validate_filter(op.filter)
        namespaces = [
            ns for ns in sorted(self.roots) if ns[: len(op.namespace_prefix)] == op.namespace_prefix
        ]
        if not namespaces and op.namespace_prefix:
            raise ToolError("memory_scope_denied", "Memory query is outside the allowed scopes")
        records = []
        for ns in namespaces:
            snapshot = self.read(ns)
            for key, record in sorted(snapshot["records"].items()):
                if op.filter is None or matches(record["value"], op.filter):
                    records.append((ns, key, record))
        return records

    def namespaces(self, op):
        self.pagination(op.limit, op.offset)
        if op.max_depth is not None and (type(op.max_depth) is not int or op.max_depth < 1):
            raise ToolError("invalid_memory_namespace", "Namespace depth must be positive")
        conditions = op.match_conditions or ()
        for condition in conditions:
            if condition.match_type not in {"prefix", "suffix"} or any(
                not isinstance(p, str) for p in condition.path
            ):
                raise ToolError(
                    "invalid_memory_namespace", "Namespace matching condition is invalid"
                )
        result = []
        for ns in sorted(self.roots):
            if not self.read(ns)["records"]:
                continue

            def match(condition, namespace=ns):
                path = condition.path
                section = (
                    namespace[: len(path)]
                    if condition.match_type == "prefix"
                    else namespace[len(namespace) - len(path) :]
                )
                return len(path) <= len(namespace) and all(
                    p == "*" or p == s for p, s in zip(path, section, strict=True)
                )

            if all(match(c) for c in conditions):
                result.append(ns[: op.max_depth] if op.max_depth else ns)
        return sorted(set(result))[op.offset : op.offset + op.limit]

    def dispatch(self, op):
        if isinstance(op, (GetOp, PutOp)):
            self.check_target(op.namespace, op.key)
            if isinstance(op, GetOp):
                records = self.read(op.namespace)["records"]
                key = resolve_memory_key(records, op.key)
                record = records.get(key)
                return self.item(op.namespace, key, record) if record else None
            if op.ttl is not None or (op.index is not None and op.index is not False):
                raise ToolError(
                    "memory_capability_unsupported",
                    "Memory TTL and vector indexing are unsupported",
                )
            self.write_changes(
                op.namespace, {op.key: normalize_value(op.value) if op.value is not None else None}
            )
            return None
        if isinstance(op, ListNamespacesOp):
            return self.namespaces(op)
        if isinstance(op, SearchOp) and op.query is None:
            return self.finish_search(op, self.search_records(op))
        raise TypeError("Unsupported memory store operation")

    def finish_search(self, op, records, scores=None):
        records = records[op.offset : op.offset + op.limit]
        if sum(len(encode(record["value"])) for _, _, record in records) > 64000:
            raise ToolError("memory_query_budget", "Memory result bodies exceed the return budget")
        return [
            self.item(ns, key, record, score=scores[(ns, key)] if scores else None)
            for ns, key, record in records
        ]

    async def query(self, op):
        if not isinstance(op.query, str) or not op.query.strip() or len(op.query) > 64000:
            raise ToolError("memory_query_budget", "Memory query must be bounded nonblank text")
        if estimate_text(op.query) + 128 >= self.directory_tokens:
            raise ToolError("memory_query_budget", "Memory query exceeds its token budget")
        records = await owned_io(self.search_records, op)
        if not records or op.limit == 0:
            return []
        directories = []
        current = []
        cost = estimate_text(op.query) + 128
        for ns, key, record in records:
            content = record["value"]["content"]
            entry = {
                "id": json.dumps([ns, key]),
                "kind": record["value"]["kind"],
                "title": content["title"],
                "summary": content["summary"],
            }
            tokens = estimate_text(encode(entry).decode()) + 8
            if tokens + estimate_text(op.query) + 128 > self.directory_tokens:
                raise ToolError(
                    "memory_query_budget", "A memory directory entry exceeds its budget"
                )
            if cost + tokens > self.directory_tokens:
                directories.append(current)
                current = []
                cost = estimate_text(op.query) + 128
            current.append(entry)
            cost += tokens
        if current:
            directories.append(current)
        if len(directories) > self.max_queries:
            raise ToolError("memory_query_budget", "Memory directory exceeds the model call budget")
        scores = {}
        for directory in directories:
            choices = await self.selector(op.query, copy.deepcopy(directory))
            if not isinstance(choices, list):
                raise ToolError("invalid_memory_selection", "Memory selector must return a list")
            allowed = {entry["id"] for entry in directory}
            seen = set()
            for choice in choices:
                if (
                    not isinstance(choice, dict)
                    or set(choice) != {"id", "score"}
                    or not isinstance(choice["id"], str)
                    or choice["id"] not in allowed
                    or choice["id"] in seen
                    or type(choice["score"]) not in {int, float}
                    or not 0 <= choice["score"] <= 1
                ):
                    raise ToolError(
                        "invalid_memory_selection",
                        "Memory selector returned an invalid identity or score",
                    )
                seen.add(choice["id"])
                ns, key = json.loads(choice["id"])
                scores[(tuple(ns), key)] = choice["score"]
        ranked = [r for r in records if (r[0], r[1]) in scores]
        ranked.sort(key=lambda r: (-scores[(r[0], r[1])], r[0], r[1]))
        return self.finish_search(op, ranked, scores)

    def batch(self, ops):
        ops = list(ops)
        if any(isinstance(op, SearchOp) and op.query is not None for op in ops):
            try:
                asyncio.get_running_loop()
            except RuntimeError:
                return asyncio.run(self.abatch(ops))
            raise ToolError("memory_async_required", "Use async memory search within an event loop")
        return [self.dispatch(op) for op in ops]

    async def abatch(self, ops):
        results = []
        for op in ops:
            result = (
                await self.query(op)
                if isinstance(op, SearchOp) and op.query is not None
                else await owned_io(self.dispatch, op)
            )
            results.append(result)
        return results

    async def snapshot(self, namespace):
        self.check_target(namespace, "snapshot")
        return await owned_io(self.read, namespace)

    async def tool_receipt_snapshot(self, namespace, identity):
        def read():

            state = self.read(namespace)
            receipt = state["tool_receipts"].get(identity)
            if receipt is None:
                return None
            revision = receipt["revision"]
            if state["revision"] != revision:
                path = safe_relative(self.roots[namespace], f"versions/revision-{revision}.json")
                if not path.exists():
                    raise StorageError(
                        "memory_tool_snapshot_missing", "Saved tool version is missing"
                    )
                try:
                    payload = path.read_bytes()
                except OSError as error:
                    raise StorageError(
                        "memory_tool_snapshot_missing", "Saved tool version cannot be read"
                    ) from error
                state = decode_memory_snapshot(payload).model_dump(mode="json")
            if (
                state["namespace"] != list(namespace)
                or state["revision"] != revision
                or state["tool_receipts"].get(identity) != receipt
                or any(
                    data_hash(state["records"].get(key)) != digest
                    if digest is not None
                    else key in state["records"]
                    for key, digest in receipt["record_versions"].items()
                )
            ):
                raise StorageError(
                    "memory_tool_conflict", "Saved tool version differs from its receipt"
                )
            return receipt, state["records"]

        return await owned_io(read)

    async def open_update(self, *, validate_change=None):
        snapshots = {}
        for ns in self.roots:
            snapshots[ns] = await owned_io(self.read, ns)
        parent = self
        if validate_change is not None:
            parent = JsonMemoryStore(
                self.roots,
                selector=self.selector,
                validate_change=validate_change,
                directory_tokens=self.directory_tokens,
                max_queries=self.max_queries,
            )
        return MemoryUpdateStore(parent, snapshots)


class MemoryUpdateStore(JsonMemoryStore):
    """Job-owned BaseStore view; component put/delete only stage, never publish."""

    def __init__(self, parent, snapshots):
        self.parent = parent
        self.roots = parent.roots
        self.selector = parent.selector
        self.validate_change = parent.validate_change
        self.directory_tokens = parent.directory_tokens
        self.max_queries = parent.max_queries
        self.snapshots = snapshots
        self.original_records = {
            ns: copy.deepcopy(state["records"]) for ns, state in snapshots.items()
        }
        self.changes = {ns: {} for ns in snapshots}
        self.lock = threading.RLock()
        self.closed = False
        self.pending = None
        self.committed = set()
        self.committing = set()

    def read(self, namespace):
        with self.lock:
            if self.closed:
                raise StorageError("memory_update_closed", "Memory update session is closed")
            return copy.deepcopy(self.snapshots[namespace])

    def write_changes(
        self,
        namespace,
        changes,
        *,
        revision=None,
        batches=None,
        group_receipts=None,
        tool_receipt=None,
    ):
        with self.lock:
            state = self.read(namespace)
            for key, value in changes.items():
                self.check_target(namespace, key)
                key = resolve_memory_key(state["records"], key)
                previous = state["records"].get(key)
                self.changes[namespace][key] = copy.deepcopy(value)
                if value is None:
                    state["records"].pop(key, None)
                else:
                    now = datetime.now(timezone.utc).isoformat()
                    state["records"][key] = {
                        "value": copy.deepcopy(value),
                        "created_at": previous["created_at"] if previous else now,
                        "updated_at": now,
                    }
            self.snapshots[namespace] = state
            return copy.deepcopy(state)

    def prepare(self):
        with self.lock:
            if self.closed and self.pending is None:
                raise StorageError("memory_update_closed", "Memory update session was discarded")
            if self.pending is None:
                self.pending = copy.deepcopy(self.changes)
            self.closed = True
            return PreparedMemoryUpdate(
                {ns: state["revision"] for ns, state in self.snapshots.items()},
                self.original_records,
                self.pending,
                {ns: state["group_receipts"] for ns, state in self.snapshots.items()},
            )

    def preview(self):
        """Inspect an immutable staging snapshot without closing it before rule optimization."""
        with self.lock:
            if self.closed:
                raise StorageError("memory_update_closed", "Memory update session is closed")
            return PreparedMemoryUpdate(
                {ns: state["revision"] for ns, state in self.snapshots.items()},
                self.original_records,
                copy.deepcopy(self.changes),
                {ns: state["group_receipts"] for ns, state in self.snapshots.items()},
            )

    def restore_rules(self, namespace, baseline_values):
        """Undo provisional rule changes without dropping independent staged facts."""
        with self.lock:
            state = self.read(namespace)
            for key, value in baseline_values.items():
                original = self.original_records[namespace].get(key)
                if value is None or (original is not None and original["value"] == value):
                    self.changes[namespace].pop(key, None)
                    if original is not None:
                        state["records"][key] = copy.deepcopy(original)
                    else:
                        state["records"].pop(key, None)
                else:
                    self.snapshots[namespace] = state
                    self.write_changes(namespace, {key: value})
                    state = self.read(namespace)
            self.snapshots[namespace] = state

    async def commit(
        self,
        namespace,
        *,
        batch_id=None,
        progress=None,
        accepted_changes=None,
        group_receipts=None,
        tool_receipt=None,
    ):
        self.check_target(namespace, batch_id or "commit")
        with self.lock:
            if self.closed and self.pending is None:
                raise StorageError("memory_update_closed", "Memory update session was discarded")
            if namespace in self.committed or namespace in self.committing:
                raise StorageError("memory_update_closed", "Memory scope is already committed")
            state = copy.deepcopy(self.snapshots[namespace])
            pending = self.pending if self.pending is not None else copy.deepcopy(self.changes)
            changes = (
                pending[namespace] if accepted_changes is None else copy.deepcopy(accepted_changes)
            )
            batches = None
            if batch_id is not None:
                payload = {
                    **(progress or {}),
                    "changes_sha256": hashlib.sha256(encode(changes)).hexdigest(),
                }
                try:
                    batches = {
                        batch_id: SourceBatch.model_validate_json(encode(payload)).model_dump(
                            mode="json"
                        )
                    }
                except (TypeError, ValueError) as error:
                    raise ToolError(
                        "invalid_memory_progress", "Memory source progress is invalid"
                    ) from error
            # Freeze this view before I/O. No late component writes may race with publication.
            self.pending = pending
            self.closed = True
            self.committing.add(namespace)
        try:
            result = await owned_io(
                lambda: self.parent.write_changes(
                    namespace,
                    changes,
                    revision=state["revision"],
                    batches=batches,
                    group_receipts=group_receipts,
                    tool_receipt=tool_receipt,
                )
            )
        finally:
            with self.lock:
                self.committing.discard(namespace)
        with self.lock:
            self.committed.add(namespace)
        return result

    def discard(self):
        with self.lock:
            self.closed = True
            self.pending = None
