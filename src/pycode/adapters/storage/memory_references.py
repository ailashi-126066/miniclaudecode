"""Read memory authorities and recovery references before any managed-object cleanup."""

import json
import re

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.storage.json_memory import (
    decode_memory_snapshot,
    encode,
    reject_duplicate_keys,
)
from pycode.adapters.storage.memory_migration import safe_relative, verify_migration_backup
from pycode.core.errors import StorageError
from pycode.core.memory_schema import MemoryMigrationReceipt


def reference_json(root, path, *, snapshot=False):
    path = safe_relative(root, path.relative_to(root).as_posix())
    try:
        payload = path.read_bytes()
        if snapshot:
            return decode_memory_snapshot(payload).model_dump(mode="json")
        value = json.loads(payload, object_pairs_hook=reject_duplicate_keys)
        if not isinstance(value, dict):
            raise ValueError("Memory reference record must be an object")
        encode(value)
        return value
    except (OSError, ValueError, TypeError) as error:
        raise StorageError("gc_unreadable_memory", "Cannot verify memory references") from error


def memory_reference_values(root):
    if root.is_symlink():
        raise StorageError("memory_path_invalid", "Memory reference root cannot be a symlink")
    if not root.exists():
        return (), False
    busy = False
    for name in ("memory.lock", "moves.lock"):
        writer = root / name
        if writer.is_symlink():
            raise StorageError("memory_path_invalid", "Memory writer lease cannot be a symlink")
        if writer.exists():
            probe = FileLease(writer)
            try:
                probe.acquire()
            except StorageError as error:
                if error.code != "session_busy":
                    raise
                busy = True
            finally:
                probe.close()
    values = []
    active = root / "memory.json"
    state = (
        reference_json(root, active, snapshot=True)
        if active.exists() or active.is_symlink()
        else None
    )
    if state is not None:
        values.append(state)
    manifest_path = root / "migration.json"
    if manifest_path.exists() or manifest_path.is_symlink():
        manifest = reference_json(root, manifest_path)
        try:
            receipt = MemoryMigrationReceipt.model_validate_json(
                encode(manifest.get("receipt"))
            ).model_dump(mode="json")
        except ValueError as error:
            raise StorageError(
                "memory_migration_corrupt", "Migration reference identity is invalid"
            ) from error
        if state is not None and state["migration"] != receipt:
            raise StorageError(
                "memory_migration_conflict", "Migration references differ from active memory"
            )
        for path in verify_migration_backup(root, receipt, manifest):
            if path.suffix == ".json":
                values.append(reference_json(root, path))
    elif state is not None and state["migration"] is not None:
        raise StorageError("memory_migration_corrupt", "Active migration has no recovery manifest")
    if state is None:
        for path in sorted(root.glob("*.json")):
            if re.fullmatch(r"[a-f0-9]{32}", path.stem):
                values.append(reference_json(root, path))
        candidates = root / "candidates"
        if candidates.is_symlink():
            raise StorageError("memory_path_invalid", "Legacy candidate root cannot be a symlink")
        if candidates.exists():
            for path in sorted(candidates.glob("*.json")):
                data = reference_json(root, path)
                if data.get("status") == "pending":
                    values.append(data)
    versions = root / "versions"
    jobs = root / "jobs"
    moves = root / "moves"
    for directory in (versions, jobs, moves):
        if directory.is_symlink():
            raise StorageError("memory_path_invalid", "Memory recovery root cannot be a symlink")
    if versions.exists():
        for path in sorted(versions.glob("revision-*.json")):
            values.append(reference_json(root, path, snapshot=True))
    if jobs.exists():
        for path in sorted(jobs.glob("*.json")):
            values.append(reference_json(root, path))
    if moves.exists():
        from pycode.core.memory_move import MemoryMove

        for path in sorted(moves.glob("*.json")):
            value = reference_json(root, path)
            try:
                checked = MemoryMove.model_validate_json(encode(value))
                if checked.move_id != path.stem:
                    raise ValueError("Move filename differs from its identity")
            except ValueError as error:
                raise StorageError(
                    "gc_unreadable_memory", "Cannot verify move references"
                ) from error
            values.append(checked.model_dump(mode="json"))
    return tuple(values), busy
