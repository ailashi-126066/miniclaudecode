"""Copy-first scope transfers use ordered leases, atomic per-scope receipts and a journal."""

import copy
import json
from contextlib import ExitStack
from datetime import datetime, timezone

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.storage.json_memory import encode, memory_lease, reject_duplicate_keys
from pycode.adapters.storage.path_io import atomic_write, owned_io, safe_relative
from pycode.core.errors import StorageError
from pycode.core.hashing import data_hash
from pycode.core.memory_move import MemoryMove, moved_value
from pycode.core.memory_schema import resolve_memory_key


class FileMemoryMoves:
    def __init__(self, store, scopes, user_namespace):
        self.store, self.scopes, self.user_namespace = store, scopes, user_namespace

    def path(self, identity):
        if len(identity) != 64 or any(c not in "0123456789abcdef" for c in identity):
            raise StorageError("memory_move_corrupt", "Move identity is invalid")
        return safe_relative(self.store.roots[self.user_namespace], "moves/" + identity + ".json")

    def read(self, identity):
        path = self.path(identity)
        if not path.exists():
            return None
        try:
            value = json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
            move = MemoryMove.model_validate_json(encode(value))
            if move.move_id != identity:
                raise ValueError("Move filename differs")
            return move
        except (OSError, ValueError, TypeError) as error:
            raise StorageError("memory_move_corrupt", "Move journal cannot be verified") from error

    def write(self, move):
        payload = move.model_dump(mode="json")
        MemoryMove.model_validate_json(encode(payload))
        if len(encode(payload)) > 2 * 1024 * 1024:
            raise StorageError("memory_move_budget", "Move journal exceeds its disk budget")
        atomic_write(self.path(move.move_id), encode(payload))

    def publish(self, namespace, state, previous):
        state["revision"] += 1
        self.store.publish(namespace, state, previous)

    def run(self, move):
        if move.source_namespace not in self.scopes or move.target_namespace not in self.scopes:
            raise StorageError("memory_scope_denied", "Move owns another project or user")
        user_root = self.store.roots[self.user_namespace]
        coordinator = FileLease(safe_relative(user_root, "moves.lock"))
        try:
            coordinator.acquire()
            saved = self.read(move.move_id)
            if saved is not None:
                if saved.identity() != move.identity():
                    raise StorageError("memory_move_conflict", "Move identity changed")
                move = saved
            else:
                self.write(move)
            if move.status == "conflict":
                return move
            roots = sorted(
                {self.store.roots[move.source_namespace], self.store.roots[move.target_namespace]},
                key=str,
            )
            with ExitStack() as locks:
                for root in roots:
                    locks.enter_context(memory_lease(root))
                source = self.store.read(move.source_namespace)
                target = self.store.read(move.target_namespace)
                source_record = move.record.model_dump(mode="json")
                target_value = moved_value(move, self.scopes[move.target_namespace])
                authority = {
                    "source_namespace": list(move.source_namespace),
                    "target_namespace": list(move.target_namespace),
                    "key": move.key,
                    "session_id": move.session_id,
                    "action_entry_id": move.action_entry_id,
                    "source_record_sha256": data_hash(source_record),
                    "target_value_sha256": data_hash(target_value),
                }
                if move.status == "completed":
                    if target["move_receipts"].get(move.move_id) != {
                        **authority,
                        "side": "target",
                    } or source["move_receipts"].get(move.move_id) != {
                        **authority,
                        "side": "source",
                    }:
                        raise StorageError(
                            "memory_move_conflict", "Completed move has no matching receipts"
                        )
                    return move
                if move.move_id not in target["move_receipts"]:
                    existing = resolve_memory_key(target["records"], move.key)
                    if (
                        data_hash(source["records"].get(move.key)) != data_hash(source_record)
                        or existing in target["records"]
                    ):
                        move.status, move.error_code = "conflict", "memory_move_target_conflict"
                        self.write(move)
                        return move
                    previous = copy.deepcopy(target)
                    record = copy.deepcopy(source_record)
                    record["value"] = target_value
                    if move.content is not None:
                        record["updated_at"] = datetime.now(timezone.utc).isoformat()
                    target["records"][move.key] = record
                    target["move_receipts"][move.move_id] = {**authority, "side": "target"}
                    self.publish(move.target_namespace, target, previous)
                elif target["move_receipts"][move.move_id] != {**authority, "side": "target"}:
                    raise StorageError("memory_move_conflict", "Target move receipt differs")
                move.status = "target_saved"
                self.write(move)
                if move.move_id not in source["move_receipts"]:
                    if data_hash(source["records"].get(move.key)) != data_hash(source_record):
                        move.status, move.error_code = "conflict", "memory_move_source_conflict"
                        self.write(move)
                        return move
                    previous = copy.deepcopy(source)
                    source["records"].pop(move.key)
                    source["move_receipts"][move.move_id] = {**authority, "side": "source"}
                    self.publish(move.source_namespace, source, previous)
                elif source["move_receipts"][move.move_id] != {**authority, "side": "source"}:
                    raise StorageError("memory_move_conflict", "Source move receipt differs")
                move.status = "completed"
                self.write(move)
                return move
        finally:
            coordinator.close()

    async def execute(self, move):
        try:
            return await owned_io(self.run, move)
        except OSError as error:
            raise StorageError(
                "memory_move_io_error",
                "Move file operation failed; restart can reconcile saved receipts",
            ) from error

    async def pending(self, session_id, workspace):
        def read():
            directory = safe_relative(self.store.roots[self.user_namespace], "moves")
            if not directory.exists():
                return ()
            values = [self.read(path.stem) for path in sorted(directory.glob("*.json"))]
            return tuple(
                move
                for move in values
                if move.session_id == session_id
                and move.workspace == workspace
                and move.status != "conflict"
            )

        return await owned_io(read)
