"""Obsolete execution data is backed up before final storage normalization."""

import json
import sqlite3

from pycode.adapters.storage.migration import (
    migrate_output_metadata,
    migrate_runtime_database,
    migrate_session_brand,
)


def test_runtime_migration_backs_up_real_database_and_preserves_accounting(tmp_path):
    path = tmp_path / "runtime.sqlite"
    with sqlite3.connect(path) as connection:
        connection.execute("CREATE TABLE model_requests (payload TEXT)")
        connection.execute("INSERT INTO model_requests VALUES ('accounting')")
        connection.execute("CREATE TABLE tool_operations (payload TEXT)")
        connection.execute("INSERT INTO tool_operations VALUES ('old completed fact')")
        connection.execute("CREATE TABLE runtime_events (payload TEXT)")
        connection.execute("CREATE TABLE checkpoints (payload TEXT)")
    migrate_runtime_database(path)
    with sqlite3.connect(path) as connection:
        names = {row[0] for row in connection.execute("SELECT name FROM sqlite_master")}
        assert names == {"model_requests"}
        assert (
            connection.execute("SELECT payload FROM model_requests").fetchone()[0] == "accounting"
        )
    backup_path = tmp_path / "migrations/deepagents-v1/runtime-before.sqlite"
    with sqlite3.connect(backup_path) as connection:
        assert (
            connection.execute("SELECT payload FROM tool_operations").fetchone()[0]
            == "old completed fact"
        )
    original = backup_path.read_bytes()
    migrate_runtime_database(path)
    assert backup_path.read_bytes() == original


def test_output_migration_backups_exact_old_metadata_before_deduplication(tmp_path):
    path = tmp_path / "output.json"
    before = {
        "storage": "inline",
        "source_entry_id": "fact",
        "preview": "original output",
        "settled_result": {"text": "original output"},
        "settlement_sha256": "legacy",
    }
    raw = json.dumps(before).encode()
    path.write_bytes(raw)
    migrated = migrate_output_metadata(path)
    assert migrated["preview"] == "" and migrated["preview_sha256"]
    assert "settled_result" not in migrated and "settlement_sha256" not in migrated
    backups = list((tmp_path / "migration-backups").glob("*.json"))
    assert len(backups) == 1 and backups[0].read_bytes() == raw
    assert migrate_output_metadata(path) == migrated


def test_brand_migration_preserves_log_remainder_branch_and_exact_backup(tmp_path):
    path = tmp_path / "entries.jsonl"
    header = {
        "record_type": "header",
        "application": "py",
        "session_id": "old",
        "workspace": str(tmp_path),
        "created_at": "t",
        "schema_version": 1,
    }
    remainder = b'{"opaque_original":"keep bytes exactly"}\n'
    raw = json.dumps(header).encode() + b"\n" + remainder
    path.write_bytes(raw)
    position = tmp_path / "position.json"
    position.write_bytes(
        json.dumps({"leaf_id": "leaf", "revision": 7, "log_offset": len(raw)}).encode()
    )
    migrate_session_brand(path)
    assert json.loads(path.read_bytes().split(b"\n", 1)[0])["application"] == "pycode"
    assert path.read_bytes().split(b"\n", 1)[1] == remainder
    assert (tmp_path / "migration-backups/entries-before-pycode.jsonl").read_bytes() == raw
    moved = json.loads(position.read_bytes())
    assert moved["leaf_id"] == "leaf" and moved["revision"] == 7
    assert moved["log_offset"] == len(path.read_bytes())
