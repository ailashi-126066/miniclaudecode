"""One-way legacy record migration; original files and exact backups remain inactive."""

import hashlib
import json
import math
import re
from datetime import datetime, timezone

from pycode.adapters.storage.json_memory import (
    encode,
    memory_lease,
    normalize_value,
    reject_duplicate_keys,
)
from pycode.adapters.storage.path_io import atomic_write, owned_io, safe_relative
from pycode.core.errors import StorageError, ToolError
from pycode.core.memory import CATEGORIES, content_hash
from pycode.core.memory_schema import MemorySnapshot

UUID_HEX = re.compile(r"[a-f0-9]{32}")


def read_json(path):
    if path.is_symlink():
        raise StorageError("memory_path_invalid", "Legacy memory path cannot be a symlink")
    try:
        return json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
    except (ValueError, UnicodeError) as error:
        raise StorageError("memory_legacy_corrupt", "Legacy memory JSON is invalid") from error


def timestamp(value):
    if type(value) not in {int, float} or not math.isfinite(value):
        raise StorageError("memory_legacy_corrupt", "Legacy memory timestamp is invalid")
    try:
        return datetime.fromtimestamp(value, timezone.utc).isoformat()
    except (ValueError, OverflowError, OSError) as error:
        raise StorageError(
            "memory_legacy_corrupt", "Legacy memory timestamp is out of range"
        ) from error


def convert_record(metadata, body, scope):
    required = {
        "memory_id",
        "key",
        "category",
        "scope",
        "created_at",
        "updated_at",
        "evidence",
        "sha256",
    }
    if (
        not isinstance(metadata, dict)
        or not required <= metadata.keys()
        or not metadata.keys() <= required | {"summary"}
    ):
        raise StorageError("memory_legacy_corrupt", "Legacy memory metadata fields are invalid")
    if (
        metadata["scope"] != scope
        or metadata["category"] not in CATEGORIES
        or metadata["sha256"] != content_hash(body)
    ):
        raise StorageError(
            "memory_legacy_corrupt", "Legacy memory scope, category or content hash differs"
        )
    if not isinstance(metadata["evidence"], list) or not metadata["evidence"]:
        raise StorageError("memory_legacy_evidence_missing", "Legacy memory lacks source evidence")
    sources = []
    for evidence in metadata["evidence"]:
        if not isinstance(evidence, dict) or not set(evidence) <= {
            "entry_id",
            "quote",
            "sha256",
            "source_session_id",
            "session_id",
        }:
            raise StorageError("memory_legacy_corrupt", "Legacy evidence fields are invalid")
        session_id = evidence.get("source_session_id", evidence.get("session_id"))
        if evidence.get("session_id", session_id) != session_id:
            raise StorageError("memory_legacy_corrupt", "Legacy evidence session identity differs")
        sources.append(
            {
                "session_id": session_id,
                "entry_id": evidence.get("entry_id"),
                "quote": evidence.get("quote"),
                "sha256": evidence.get("sha256"),
            }
        )
    try:
        value = normalize_value(
            {
                "kind": "SemanticMemory",
                "content": {
                    "title": metadata["key"],
                    "summary": metadata.get("summary") or body[:160],
                    "body": body,
                    "category": metadata["category"],
                    "sources": sources,
                },
            }
        )
    except ToolError as error:
        raise StorageError(
            "memory_legacy_corrupt", "Legacy memory content or provenance is invalid"
        ) from error
    return {
        "value": value,
        "created_at": timestamp(metadata["created_at"]),
        "updated_at": timestamp(metadata["updated_at"]),
    }


def migration_backup_path(root, receipt):
    directory = root / "migration-backups"
    backup = directory / receipt["source_sha256"]
    if directory.is_symlink() or backup.is_symlink():
        raise StorageError("memory_path_invalid", "Migration backup path is a symlink")
    return backup


def verify_migration_backup(root, receipt, manifest):
    if (
        not isinstance(manifest, dict)
        or set(manifest) != {"status", "receipt", "files"}
        or manifest.get("status") not in {"prepared", "complete"}
        or receipt != manifest.get("receipt")
    ):
        raise StorageError("memory_migration_conflict", "Published migration identity differs")
    files = manifest["files"]
    if (
        not isinstance(files, dict)
        or any(
            not isinstance(name, str)
            or not isinstance(digest, str)
            or not re.fullmatch(r"[a-f0-9]{64}", digest)
            for name, digest in files.items()
        )
        or hashlib.sha256(encode(files)).hexdigest() != receipt["source_sha256"]
    ):
        raise StorageError("memory_migration_corrupt", "Migration manifest checksum differs")
    backup = migration_backup_path(root, receipt)
    for name, digest in files.items():
        archived = safe_relative(backup, name)
        if not archived.is_file() or hashlib.sha256(archived.read_bytes()).hexdigest() != digest:
            raise StorageError(
                "memory_migration_corrupt", "Inactive migration backup is missing or changed"
            )
        # Once complete, original files are recovery material, never a live read authority.
        if manifest["status"] == "prepared":
            original = safe_relative(root, name)
            if (
                not original.is_file()
                or hashlib.sha256(original.read_bytes()).hexdigest() != digest
            ):
                raise StorageError(
                    "memory_migration_conflict", "Legacy source changed during migration"
                )
    return tuple(safe_relative(backup, name) for name in files)


class MemoryMigration:
    def __init__(self, store, scopes, *, recover):
        if set(scopes) != set(store.roots) or any(
            scope not in {"user", "project"} for scope in scopes.values()
        ):
            raise ValueError("Migration requires a trusted scope for every configured namespace")
        self.store = store
        self.scopes = scopes
        self.recover = recover

    async def run(self):
        results = {}
        for namespace, scope in self.scopes.items():
            results[namespace] = await owned_io(self.migrate_scope, namespace, scope)
        return results

    def legacy_files(self, root):
        files = [
            path
            for path in root.iterdir()
            if UUID_HEX.fullmatch(path.stem) and path.suffix in {".json", ".md"}
        ]
        index = root / "MEMORY.md"
        if index.exists():
            files.append(index)
        for folder in ("candidates", "versions", "transactions"):
            directory = root / folder
            if directory.is_symlink():
                raise StorageError("memory_path_invalid", "Legacy auxiliary directory is a symlink")
            if not directory.exists():
                continue
            for entry in directory.iterdir():
                if folder == "candidates":
                    if UUID_HEX.fullmatch(entry.stem) and entry.suffix == ".json":
                        files.append(entry)
                elif UUID_HEX.fullmatch(entry.name):
                    if entry.is_symlink() or not entry.is_dir():
                        raise StorageError(
                            "memory_path_invalid", "Legacy recovery directory is invalid"
                        )
                    files.extend(entry.rglob("*"))
        for path in files:
            if path.is_symlink():
                raise StorageError("memory_path_invalid", "Legacy memory file is a symlink")
        return sorted(
            (p for p in files if p.is_file()), key=lambda p: p.relative_to(root).as_posix()
        )

    def finish(self, root, state, manifest):
        receipt = state["migration"]
        verify_migration_backup(root, receipt, manifest)
        if manifest["status"] != "complete":
            atomic_write(root / "migration.json", encode({**manifest, "status": "complete"}))
        return {
            "status": "migrated",
            "count": len(receipt["record_ids"]),
            "source_sha256": receipt["source_sha256"],
        }

    def migrate_scope(self, namespace, scope):
        root = self.store.roots[namespace]
        if not root.exists():
            return {"status": "empty", "count": 0}
        try:
            with memory_lease(root):
                active = root / "memory.json"
                manifest_path = root / "migration.json"
                if active.exists():
                    state = self.store.read(namespace)
                    if state["migration"] is None:
                        if self.legacy_files(root) or any(
                            (root / name).exists()
                            for name in ("semantic.md", "episodic.md", "procedural.md")
                        ):
                            raise StorageError(
                                "memory_migration_conflict",
                                "Active JSON and unconverted legacy memory coexist",
                            )
                        return {"status": "json", "count": len(state["records"])}
                    if not manifest_path.exists():
                        raise StorageError(
                            "memory_migration_corrupt", "Published migration lacks its manifest"
                        )
                    return self.finish(root, state, read_json(manifest_path))
                for name in ("semantic.md", "episodic.md", "procedural.md"):
                    if (root / name).exists():
                        raise StorageError(
                            "memory_legacy_format_unrecognized",
                            "Legacy aggregate Markdown has no verified record schema",
                        )
                self.recover(root)
                paths = self.legacy_files(root)
                if not paths:
                    if manifest_path.exists():
                        raise StorageError(
                            "memory_migration_corrupt", "Prepared migration lost its legacy sources"
                        )
                    return {"status": "empty", "count": 0}
                files = {
                    p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in paths
                }
                records = {}
                metadata_paths = [
                    p
                    for p in paths
                    if p.parent == root and p.suffix == ".json" and UUID_HEX.fullmatch(p.stem)
                ]
                for path in metadata_paths:
                    metadata = read_json(path)
                    body_path = path.with_suffix(".md")
                    if body_path not in paths:
                        raise StorageError("memory_legacy_corrupt", "Legacy memory body is missing")
                    if not isinstance(metadata, dict) or metadata.get("memory_id") != path.stem:
                        raise StorageError(
                            "memory_legacy_corrupt",
                            "Legacy memory identity differs from its filename",
                        )
                    record = convert_record(metadata, body_path.read_bytes().decode("utf-8"), scope)
                    self.store.validate_change(namespace, path.stem, record["value"], None)
                    records[path.stem] = record
                bodies = {
                    p.stem
                    for p in paths
                    if p.parent == root and p.suffix == ".md" and UUID_HEX.fullmatch(p.stem)
                }
                if bodies != set(records):
                    raise StorageError("memory_legacy_corrupt", "Legacy memory metadata is missing")
                receipt = {
                    "format": "legacy-records-v1",
                    "source_sha256": hashlib.sha256(encode(files)).hexdigest(),
                    "record_ids": sorted(records),
                }
                state = MemorySnapshot(namespace=namespace).model_dump(mode="json")
                state.update(records=records, revision=1, migration=receipt)
                try:
                    MemorySnapshot.model_validate_json(encode(state))
                except ValueError as error:
                    raise StorageError(
                        "memory_legacy_corrupt",
                        "Legacy record schema or timestamp order is invalid",
                    ) from error
                manifest = {"status": "prepared", "receipt": receipt, "files": files}
                if manifest_path.exists() and read_json(manifest_path) != manifest:
                    raise StorageError(
                        "memory_migration_conflict", "Prepared migration sources changed"
                    )
                backup = migration_backup_path(root, receipt)
                for path in paths:
                    destination = safe_relative(backup, path.relative_to(root).as_posix())
                    if destination.exists() and destination.read_bytes() != path.read_bytes():
                        raise StorageError(
                            "memory_migration_corrupt", "Migration backup differs from its source"
                        )
                    if not destination.exists():
                        atomic_write(destination, path.read_bytes())
                atomic_write(manifest_path, encode(manifest))
                self.store.publish(namespace, state, None)
                return self.finish(root, state, manifest)
        except UnicodeError as error:
            raise StorageError(
                "memory_legacy_corrupt", "Legacy memory body is not UTF-8"
            ) from error
        except OSError as error:
            raise StorageError(
                "memory_migration_io_error", "Memory migration file operation failed"
            ) from error
