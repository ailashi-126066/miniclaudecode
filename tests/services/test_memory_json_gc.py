"""GC respects JSON memory, version/deletion recovery and unfinished writer references."""

import json
from pathlib import Path

import pytest
from tests.adapters.storage.test_json_memory import PROJECT, USER, value
from tests.services.test_storage_gc import setup_objects, write_output
from tests.support.legacy_memory_store import FileMemoryStore
from tests.support.legacy_memory_types import MemoryRecord

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.storage.json_memory import JsonMemoryStore
from pycode.adapters.storage.memory_migration import MemoryMigration
from pycode.adapters.storage.path_io import owned_io
from pycode.core.errors import StorageError
from pycode.services.storage_gc import StorageGC


async def setup_memory_objects(tmp_path):
    workspace, layout, timing, repository, objects = await setup_objects(tmp_path)
    output_id, entry_id = await write_output(repository, layout, workspace)
    user_root = objects.root / "memory"
    project_root = workspace / ".pycode" / "memory"

    async def choose(query, directory):
        return []

    store = JsonMemoryStore(
        {USER: user_root, PROJECT: project_root},
        selector=choose,
        validate_change=lambda *args: None,
    )
    gc = StorageGC(objects, repository, timing)
    await gc.delete_session("s1")
    return store, gc, objects, workspace, layout, output_id, entry_id


def reference(entry_id):
    data = value()
    data["content"]["sources"] = [{"session_id": "s1", "entry_id": entry_id, "quote": "line"}]
    return data


async def test_tool_receipt_alone_protects_source_history_after_confirmed_deletion(tmp_path):
    from pycode.core.memory_tool_receipt import receipt_id
    from pycode.core.tools import ToolContext

    store, gc, _, workspace, _, output_id, entry_id = await setup_memory_objects(tmp_path)
    context = ToolContext("s1", "run", "operation", str(workspace), "call", "manage_user_memory")
    metadata = {
        "session_id": "s1",
        "run_id": "run",
        "operation_id": "operation",
        "workspace": str(workspace),
        "call_id": "call",
        "name": "manage_user_memory",
        "arguments_sha256": "a" * 64,
        "source_leaf_id": entry_id,
        "source_prefix_hash": "b" * 64,
    }
    store.write_changes(
        USER,
        {"deleted": None},
        tool_receipt=(receipt_id(USER, context, context.tool_name), metadata),
    )
    state = store.read(USER)
    assert not state["records"] and not state["processed_batches"] and state["tool_receipts"]
    decisions = (await gc.collect())["decisions"]
    assert next(d for d in decisions if d["type"] == "output")["object_id"].endswith(output_id)
    assert next(d for d in decisions if d["type"] == "output")["action"] == "keep_referenced"
    assert (
        next(d for d in decisions if d["type"] == "session")["action"]
        == "keep_referenced_quarantine"
    )


@pytest.mark.parametrize("namespace", [USER, PROJECT])
async def test_active_json_memory_protects_deleted_source_history_and_output(tmp_path, namespace):
    store, gc, _, _, _, output_id, entry_id = await setup_memory_objects(tmp_path)
    await store.aput(namespace, "remember", reference(entry_id))
    decisions = (await gc.collect())["decisions"]
    output = next(d for d in decisions if d["type"] == "output")
    session = next(d for d in decisions if d["type"] == "session")
    assert output["object_id"].endswith(output_id) and output["action"] == "keep_referenced"
    assert session["action"] == "keep_referenced_quarantine"
    assert output["referenced_at_collection"] and session["referenced_at_collection"]


async def test_deleted_memory_version_still_protects_recovery_sources(tmp_path):
    store, gc, _, _, _, _, entry_id = await setup_memory_objects(tmp_path)
    await store.aput(USER, "remember", reference(entry_id))
    await store.adelete(USER, "remember")
    assert not store.read(USER)["records"]
    assert (store.roots[USER] / "versions" / "revision-1.json").exists()
    decisions = (await gc.collect())["decisions"]
    assert next(d for d in decisions if d["type"] == "output")["action"] == "keep_referenced"
    assert (
        next(d for d in decisions if d["type"] == "session")["action"]
        == "keep_referenced_quarantine"
    )


async def test_pending_job_references_protect_sources_before_memory_publication(tmp_path):
    store, gc, _, _, _, output_id, entry_id = await setup_memory_objects(tmp_path)
    jobs = store.roots[USER] / "jobs"
    jobs.mkdir(parents=True)
    (jobs / "job.json").write_text(
        json.dumps(
            {
                "status": "pending_retry",
                "session_id": "s1",
                "entry_ids": [entry_id],
                "output_id": output_id,
            }
        )
    )
    decisions = (await gc.collect())["decisions"]
    assert next(d for d in decisions if d["type"] == "output")["action"] == "keep_referenced"
    assert (
        next(d for d in decisions if d["type"] == "session")["action"]
        == "keep_referenced_quarantine"
    )


async def test_prepared_scope_move_protects_sources_before_target_publication(tmp_path):
    from pycode.core.hashing import data_hash
    from pycode.core.memory_schema import validate_memory_value

    store, gc, _, workspace, _, _, entry_id = await setup_memory_objects(tmp_path)
    identity = {
        "source_namespace": list(USER),
        "target_namespace": list(PROJECT),
        "key": "moving",
        "record": {
            "value": validate_memory_value(reference(entry_id)),
            "created_at": "2026-10-14T00:00:00Z",
            "updated_at": "2026-10-14T00:00:00Z",
        },
        "content": None,
        "session_id": "s1",
        "workspace": str(workspace),
        "source_leaf_id": entry_id,
        "source_prefix_hash": "a" * 64,
        "action_entry_id": entry_id,
    }
    directory = store.roots[USER] / "moves"
    directory.mkdir(parents=True)
    move_id = data_hash(identity)
    (directory / (move_id + ".json")).write_text(json.dumps({**identity, "move_id": move_id}))
    assert not (store.roots[USER] / "memory.json").exists()
    decisions = (await gc.collect())["decisions"]
    assert next(d for d in decisions if d["type"] == "output")["action"] == "keep_referenced"
    assert (
        next(d for d in decisions if d["type"] == "session")["action"]
        == "keep_referenced_quarantine"
    )


@pytest.mark.parametrize("namespace", [USER, PROJECT])
async def test_active_memory_writer_protects_objects_before_snapshot_replace(tmp_path, namespace):
    store, gc, _, _, _, _, _ = await setup_memory_objects(tmp_path)
    lease = FileLease(store.roots[namespace] / "memory.lock")
    lease.acquire()
    try:
        decisions = (await gc.collect(dry_run=True))["decisions"]
        assert all(
            d["action"] == "keep_protected" for d in decisions if d["type"] in {"output", "session"}
        )
    finally:
        lease.close()


async def test_migration_recovery_backup_preserves_old_sources_without_old_activity(tmp_path):
    store, gc, _, _, _, _, entry_id = await setup_memory_objects(tmp_path)
    legacy = FileMemoryStore(store.roots[USER], store.roots[PROJECT])
    record = MemoryRecord(
        "a" * 32,
        "fact",
        "user_preference",
        "user",
        "line was observed",
        1,
        2,
        evidence=({"source_session_id": "s1", "entry_id": entry_id, "quote": "line"},),
    )
    await legacy.write(record)
    migration = MemoryMigration(
        store, {USER: "user", PROJECT: "project"}, recover=legacy.recover_transactions
    )
    await migration.run()
    await store.adelete(USER, record.memory_id)
    decisions = (await gc.collect())["decisions"]
    assert next(d for d in decisions if d["type"] == "output")["action"] == "keep_referenced"
    assert (
        next(d for d in decisions if d["type"] == "session")["action"]
        == "keep_referenced_quarantine"
    )
    with pytest.raises(StorageError):
        await legacy.list_records()


@pytest.mark.parametrize("location", ["active", "version", "job", "migration_backup"])
async def test_corrupt_json_memory_or_recovery_data_blocks_gc_before_any_move(tmp_path, location):
    store, gc, objects, _, _, _, entry_id = await setup_memory_objects(tmp_path)
    await store.aput(USER, "remember", reference(entry_id))
    root = store.roots[USER]
    if location == "active":
        path = root / "memory.json"
    elif location == "version":
        await store.adelete(USER, "remember")
        path = root / "versions" / "revision-1.json"
    elif location == "job":
        (root / "jobs").mkdir()
        path = root / "jobs" / "job.json"
    else:
        # A recovery manifest must not silently pretend a missing archive is valid.
        data = json.loads((root / "memory.json").read_bytes())
        receipt = {"format": "legacy-records-v1", "source_sha256": "a" * 64, "record_ids": []}
        data["migration"] = receipt
        (root / "memory.json").write_text(json.dumps(data))
        path = root / "migration.json"
    path.write_bytes(b'{"status":"pending_retry","status":"complete"}')
    before = (
        {str(p): p.read_bytes() for p in objects.quarantine.rglob("*") if p.is_file()}
        if objects.quarantine.exists()
        else {}
    )
    with pytest.raises(StorageError):
        await gc.collect()
    after = (
        {str(p): p.read_bytes() for p in objects.quarantine.rglob("*") if p.is_file()}
        if objects.quarantine.exists()
        else {}
    )
    assert after == before


async def test_user_memory_directory_symlink_cannot_escape_reference_roots(tmp_path):
    store, gc, _, _, _, _, _ = await setup_memory_objects(tmp_path)
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "fake.json").write_text("{}")
    store.roots[USER].symlink_to(outside, target_is_directory=True)
    with pytest.raises(StorageError) as error:
        await gc.collect()
    assert error.value.code == "memory_path_invalid"


@pytest.mark.parametrize("protection", ["new_reference", "active_writer"])
async def test_gc_mutation_rechecks_memory_after_initial_inventory(tmp_path, protection):
    store, _, objects, _, _, _, entry_id = await setup_memory_objects(tmp_path)
    original = await objects.inventory()
    item = next(o for o in original.objects if o.kind == "output")
    before = await owned_io(lambda: {path: Path(path).read_bytes() for path in item.files})
    lease = None
    if protection == "new_reference":
        await store.aput(USER, "late-reference", reference(entry_id))
    else:
        lease = FileLease(store.roots[USER] / "memory.lock")
        lease.acquire()
    try:
        with pytest.raises(StorageError) as error:
            await objects.apply(item, "quarantine", 1000)
        assert error.value.code == "gc_reference_changed"
        after = await owned_io(lambda: {path: Path(path).read_bytes() for path in item.files})
        assert after == before
        assert not objects.record_path(item.object_id).exists()
    finally:
        if lease is not None:
            lease.close()
