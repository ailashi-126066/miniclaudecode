"""Back up obsolete execution storage before reducing it to final object metadata."""

import hashlib
import json
import os
import sqlite3
from pathlib import Path

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.tools.backend import atomic_write

OBSOLETE_TABLES = frozenset(
    {
        "tool_operations",
        "runtime_events",
        "hook_lifetimes",
        "checkpoints",
        "checkpoint_blobs",
        "checkpoint_writes",
        "checkpoint_migrations",
    }
)


def migrate_runtime_database(path):
    path = Path(path)
    if not path.exists():
        return
    lease = FileLease(path.parent / "runtime-migration.lock")
    lease.acquire()
    connection = sqlite3.connect(path)
    try:
        names = set()
        for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'"):
            names.add(row[0])
        obsolete = sorted(names & OBSOLETE_TABLES)
        if not obsolete:
            return
        directory = path.parent / "migrations" / "deepagents-v1"
        directory.mkdir(parents=True, exist_ok=True)
        backup_path = directory / "runtime-before.sqlite"
        if not backup_path.exists():
            temporary = directory / "runtime-before.partial"
            backup = sqlite3.connect(temporary)
            try:
                connection.backup(backup)
            finally:
                backup.close()
            with temporary.open("r+b") as stream:
                os.fsync(stream.fileno())
            os.replace(temporary, backup_path)
        # Only fixed names are interpolated; model accounting is retained.
        with connection:
            for name in obsolete:
                connection.execute('DROP TABLE IF EXISTS "' + name + '"')
        digest = hashlib.sha256(backup_path.read_bytes()).hexdigest()
        atomic_write(
            directory / "runtime-migration.json",
            json.dumps(
                {
                    "version": 1,
                    "backup_sha256": digest,
                    "removed_tables": obsolete,
                },
                sort_keys=True,
            ).encode(),
        )
    finally:
        connection.close()
        lease.close()


def migrate_output_metadata(path):
    raw = path.read_bytes()
    data = json.loads(raw)
    if not data.get("preview") and not {"settled_result", "settlement_sha256"} & set(data):
        return data
    digest = hashlib.sha256(raw).hexdigest()
    backup = path.parent / "migration-backups" / (path.stem + "-" + digest + ".json")
    if not backup.exists():
        atomic_write(backup, raw)
    preview = data.get("preview", "")
    if data["storage"] == "inline" and data.get("source_entry_id") is None:
        # A pre-JSONL legacy result is uncertain, never a success recovery.
        atomic_write(path.with_suffix(".txt"), preview.encode())
        data["storage"] = "file"
        data["status"] = "interrupted"
    data["preview_sha256"] = hashlib.sha256(preview.encode()).hexdigest()
    data["preview"] = ""
    data.pop("settled_result", None)
    data.pop("settlement_sha256", None)
    atomic_write(path, json.dumps(data, ensure_ascii=False, sort_keys=True).encode())
    return data


def migrate_session_brand(path):
    if not path.exists():
        return
    with path.open("rb") as stream:
        header = stream.readline()
    data = json.loads(header)
    if data.get("record_type") != "header" or data.get("application") != "py":
        return
    lease = FileLease(path.parent / "writer.lock")
    lease.acquire()
    try:
        raw = path.read_bytes()
        header, remainder = raw.split(b"\n", 1)
        data = json.loads(header)
        if data.get("application") != "py":
            return
        backup = path.parent / "migration-backups" / "entries-before-pycode.jsonl"
        if not backup.exists():
            atomic_write(backup, raw)
        data["application"] = "pycode"
        converted = json.dumps(
            data, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode()
        atomic_write(path, converted + b"\n" + remainder)
        # Position offsets are derived from the original byte length; update
        # their offset without changing the selected branch or revision.
        position = path.parent / "position.json"
        if position.exists():
            saved = json.loads(position.read_bytes())
            saved["log_offset"] += len(converted) - len(header)
            atomic_write(position, json.dumps(saved, sort_keys=True).encode())
    finally:
        lease.close()
