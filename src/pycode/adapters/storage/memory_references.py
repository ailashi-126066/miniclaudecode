"""Read memory authorities and recovery references before any managed-object cleanup."""

import json

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.storage.json_memory import (
    decode_memory_snapshot,
    encode,
    reject_duplicate_keys,
)
from pycode.adapters.storage.path_io import safe_relative
from pycode.core.errors import StorageError


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
