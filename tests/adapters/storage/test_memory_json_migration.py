"""Actual old writer records migrate once, preserving content, identity and recovery bytes."""

import json
from dataclasses import replace
from datetime import datetime, timezone
from uuid import UUID, uuid4

import pytest
from langmem import create_manage_memory_tool
from tests.adapters.storage.test_json_memory import PROJECT, USER, make_store
from tests.support.legacy_memory_store import FileMemoryStore
from tests.support.legacy_memory_types import MemoryRecord

from pycode.adapters.storage.legacy_memory import LegacyMemoryRecovery
from pycode.adapters.storage.memory_migration import MemoryMigration
from pycode.core.errors import StorageError
from pycode.core.memory import content_hash
from pycode.core.memory_schema import MemoryValue


def setup(tmp_path):
    legacy = FileMemoryStore(tmp_path / "user", tmp_path / "project")
    store, selector, checks = make_store(tmp_path)
    migrator = MemoryMigration(
        store,
        {USER: "user", PROJECT: "project"},
        recover=LegacyMemoryRecovery().recover_transactions,
    )
    return legacy, store, migrator, selector, checks


async def seed(legacy, *, scope="user", category="user_preference"):
    record = MemoryRecord(
        uuid4().hex,
        "代码讲解偏好",
        category,
        scope,
        "```python\ndef f(x):\n    return x\n```\r\n解释参数传递。",
        1700000000.25,
        1700000010.5,
        evidence=(
            {
                "entry_id": "e",
                "source_session_id": "s",
                "quote": "以后解释参数怎么传",
                "sha256": content_hash("以后解释参数怎么传"),
            },
        ),
    )
    await legacy.write(record)
    return record


def managed_bytes(root):
    return {
        p.relative_to(root).as_posix(): p.read_bytes()
        for p in root.rglob("*")
        if p.is_file()
        and p.name != "memory.lock"
        and "migration-backups" not in p.parts
        and p.name not in {"memory.json", "migration.json"}
    }


async def test_actual_legacy_records_preserve_id_body_evidence_scope_and_times(tmp_path):
    legacy, store, migration, selector, checks = setup(tmp_path)
    user = await seed(legacy)
    project = await seed(legacy, scope="project", category="project_knowledge")
    old = {USER: managed_bytes(tmp_path / "user"), PROJECT: managed_bytes(tmp_path / "project")}
    result = await migration.run()
    for ns, record in [(USER, user), (PROJECT, project)]:
        assert result[ns]["status"] == "migrated" and result[ns]["count"] == 1
        item = await store.aget(ns, record.memory_id)
        assert item.key == record.memory_id and item.value["kind"] == "SemanticMemory"
        assert item.value["content"]["body"] == record.content
        assert item.value["content"]["category"] == record.category
        assert item.created_at == datetime.fromtimestamp(record.created_at, timezone.utc)
        assert item.updated_at == datetime.fromtimestamp(record.updated_at, timezone.utc)
        source = item.value["content"]["sources"][0]
        assert source == {
            "session_id": "s",
            "entry_id": "e",
            "quote": "以后解释参数怎么传",
            "sha256": content_hash("以后解释参数怎么传"),
        }
        root = store.roots[ns]
        assert managed_bytes(root) == old[ns]
        receipt = store.read(ns)["migration"]
        backup = root / "migration-backups" / receipt["source_sha256"]
        manifest = json.loads((root / "migration.json").read_bytes())
        assert manifest["status"] == "complete"
        for name in manifest["files"]:
            assert (backup / name).read_bytes() == old[ns][name]
        assert not store.read(ns)["processed_batches"]
    assert len(checks) == 2 and not selector.calls
    for action in [legacy.list_records, legacy.list_index, legacy.candidates]:
        with pytest.raises(StorageError) as error:
            await action()
        assert error.value.code == "memory_store_migrated"
    with pytest.raises(StorageError):
        await legacy.write(
            replace(user, content="old writer must stop"), expected_hash=content_hash(user.content)
        )


async def test_rerun_ignores_inactive_original_changes_and_does_not_overwrite_new_edits(tmp_path):
    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    await migration.run()
    value = (await store.aget(USER, record.memory_id)).value
    value["content"]["body"] = "新的主动修正"
    await store.aput(USER, record.memory_id, value)
    revision = store.read(USER)["revision"]
    (tmp_path / "user" / (record.memory_id + ".md")).write_text("inactive altered recovery copy")
    await migration.run()
    assert store.read(USER)["revision"] == revision
    assert (await store.aget(USER, record.memory_id)).value["content"]["body"] == "新的主动修正"


async def test_native_manage_uuid_update_and_delete_keep_migrated_hex_key(tmp_path):
    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    await migration.run()
    manage = create_manage_memory_tool(namespace=USER, schema=MemoryValue, store=store)
    value = (await store.aget(USER, record.memory_id)).value
    value["content"]["body"] = "更新后的参数说明"
    await manage.ainvoke({"action": "update", "id": record.memory_id, "content": value})
    items = await store.asearch(USER)
    assert len(items) == 1 and items[0].key == record.memory_id
    assert (await store.aget(USER, str(UUID(record.memory_id)))).key == record.memory_id
    view = await store.open_update()
    await view.aput(USER, str(UUID(record.memory_id)), value)
    assert (await view.aget(USER, record.memory_id)).key == record.memory_id
    await view.commit(USER)
    await manage.ainvoke({"action": "delete", "id": record.memory_id})
    assert await store.aget(USER, record.memory_id) is None and not await store.asearch(USER)
    await migration.run()
    assert not await store.asearch(USER)


@pytest.mark.parametrize("failure_point", ["backup", "publish", "complete"])
async def test_migration_failure_restores_from_same_prepared_identity(
    tmp_path, monkeypatch, failure_point
):
    import pycode.adapters.storage.memory_migration as module

    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    before = managed_bytes(tmp_path / "user")
    original_write, original_publish = module.atomic_write, store.publish

    def fail_write(path, data):
        if failure_point == "backup" and "migration-backups" in path.parts:
            raise OSError("test backup failure")
        if (
            failure_point == "complete"
            and path.name == "migration.json"
            and json.loads(data)["status"] == "complete"
        ):
            raise OSError("test completion failure")
        return original_write(path, data)

    def fail_publish(*args):
        raise OSError("test publication failure")

    monkeypatch.setattr(module, "atomic_write", fail_write)
    if failure_point == "publish":
        monkeypatch.setattr(store, "publish", fail_publish)
    with pytest.raises(StorageError) as error:
        await migration.run()
    assert error.value.code == "memory_migration_io_error"
    assert managed_bytes(tmp_path / "user") == before
    assert (tmp_path / "user" / "memory.json").exists() == (failure_point == "complete")
    monkeypatch.setattr(module, "atomic_write", original_write)
    monkeypatch.setattr(store, "publish", original_publish)
    result = await migration.run()
    assert result[USER]["count"] == 1 and store.read(USER)["revision"] == 1
    assert (await store.aget(USER, record.memory_id)).value["content"]["body"] == record.content
    assert json.loads((tmp_path / "user" / "migration.json").read_bytes())["status"] == "complete"


async def test_existing_prepared_two_file_legacy_transaction_recovers_before_capture(tmp_path):
    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    root = tmp_path / "user"
    pending = root / "transactions" / uuid4().hex
    pending.mkdir()
    content = record.content + "\n保持说明简短。"
    data = json.loads((root / (record.memory_id + ".json")).read_bytes())
    data.update(
        sha256=content_hash(content), summary=content[:160], updated_at=record.updated_at + 1
    )
    (pending / "content.md").write_bytes(content.encode())
    (pending / "record.json").write_text(json.dumps(data))
    (pending / "manifest.json").write_text(
        json.dumps({"memory_id": record.memory_id, "sha256": data["sha256"], "status": "prepared"})
    )
    await migration.run()
    assert (await store.aget(USER, record.memory_id)).value["content"]["body"] == content
    assert json.loads((pending / "manifest.json").read_bytes())["status"] == "complete"
    receipt = store.read(USER)["migration"]
    archived = (
        root
        / "migration-backups"
        / receipt["source_sha256"]
        / "transactions"
        / pending.name
        / "manifest.json"
    )
    assert json.loads(archived.read_bytes())["status"] == "complete"


@pytest.mark.parametrize(
    "corruption",
    [
        "checksum",
        "duplicate_keys",
        "missing_body",
        "missing_metadata",
        "evidence",
        "wrong_scope",
        "missing_session",
        "time",
        "secret",
    ],
)
async def test_invalid_old_records_fail_without_publishing_or_discarding_sources(
    tmp_path, corruption
):
    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    root = tmp_path / "user"
    meta = root / (record.memory_id + ".json")
    body = root / (record.memory_id + ".md")
    data = json.loads(meta.read_bytes())
    if corruption == "checksum":
        body.write_text("changed body")
    elif corruption == "duplicate_keys":
        meta.write_text(
            meta.read_text().replace('"scope": "user"', '"scope": "project", "scope": "user"')
        )
    elif corruption == "missing_body":
        body.rename(root / "saved-body.backup")
    elif corruption == "missing_metadata":
        meta.rename(root / "saved-meta.backup")
    else:
        if corruption == "evidence":
            data["evidence"] = []
        elif corruption == "wrong_scope":
            data["scope"] = "project"
        elif corruption == "missing_session":
            data["evidence"][0].pop("source_session_id")
        elif corruption == "time":
            data["created_at"] = data["updated_at"] + 10
        else:
            text = "api_key=abcdefghijklmnop"
            body.write_text(text)
            data.update(sha256=content_hash(text), summary=text)
        meta.write_text(json.dumps(data))
    before = managed_bytes(root)
    with pytest.raises(StorageError):
        await migration.run()
    assert not (root / "memory.json").exists() and managed_bytes(root) == before


async def test_partial_scopes_do_not_claim_cross_file_atomicity(tmp_path):
    legacy, store, migration, _, _ = setup(tmp_path)
    user = await seed(legacy)
    project = await seed(legacy, scope="project", category="project_knowledge")
    path = tmp_path / "project" / (project.memory_id + ".json")
    original = path.read_bytes()
    path.write_bytes(b"broken")
    with pytest.raises(StorageError):
        await migration.run()
    assert (
        await store.aget(USER, user.memory_id)
        and not (tmp_path / "project" / "memory.json").exists()
    )
    path.write_bytes(original)
    await migration.run()
    assert store.read(USER)["revision"] == store.read(PROJECT)["revision"] == 1


async def test_empty_scope_creates_no_placeholder_records_or_aggregate_files(tmp_path):
    _, store, migration, selector, _ = setup(tmp_path)
    result = await migration.run()
    assert all(v["status"] == "empty" for v in result.values())
    assert not (tmp_path / "user").exists() and not selector.calls
    assert store.read(USER)["records"] == {}


@pytest.mark.parametrize("name", ["semantic.md", "episodic.md", "procedural.md"])
async def test_unrecognized_aggregate_markdown_preserved_without_inventing_sources(tmp_path, name):
    _, _, migration, _, _ = setup(tmp_path)
    root = tmp_path / "user"
    root.mkdir()
    path = root / name
    path.write_text("Old free text with no IDs, time or evidence metadata")
    with pytest.raises(StorageError) as error:
        await migration.run()
    assert error.value.code == "memory_legacy_format_unrecognized"
    assert path.read_text().startswith("Old free text") and not (root / "memory.json").exists()


async def test_tampered_backup_or_manifest_cannot_restart_migration(tmp_path):
    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    await migration.run()
    root = tmp_path / "user"
    active = (root / "memory.json").read_bytes()
    receipt = store.read(USER)["migration"]
    path = root / "migration-backups" / receipt["source_sha256"] / (record.memory_id + ".md")
    original = path.read_bytes()
    path.write_bytes(b"changed archive")
    with pytest.raises(StorageError):
        await migration.run()
    assert (root / "memory.json").read_bytes() == active
    path.write_bytes(original)
    manifest = root / "migration.json"
    original_manifest = manifest.read_bytes()
    data = json.loads(original_manifest)
    data["files"]["../outside"] = data["files"].pop(record.memory_id + ".md")
    manifest.write_text(json.dumps(data))
    with pytest.raises(StorageError):
        await migration.run()
    assert (root / "memory.json").read_bytes() == active


async def test_active_json_with_unconverted_legacy_data_is_not_silently_merged(tmp_path):
    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    from tests.adapters.storage.test_json_memory import value

    await store.aput(USER, "new", value())
    active = (tmp_path / "user" / "memory.json").read_bytes()
    with pytest.raises(StorageError) as error:
        await migration.run()
    assert error.value.code == "memory_migration_conflict"
    assert (tmp_path / "user" / "memory.json").read_bytes() == active
    assert (tmp_path / "user" / (record.memory_id + ".md")).exists()


async def test_additive_migration_metadata_reads_original_json_v1_without_data_loss(tmp_path):
    _, store, migration, _, _ = setup(tmp_path)
    from tests.adapters.storage.test_json_memory import value

    await store.aput(USER, "existing", value())
    path = tmp_path / "user" / "memory.json"
    data = json.loads(path.read_bytes())
    data.pop("migration")
    path.write_text(json.dumps(data))
    first = await store.aget(USER, "existing")
    assert (await migration.run())[USER]["status"] == "json"
    updated = first.value
    updated["content"]["body"] = "updated existing v1"
    await store.aput(USER, "existing", updated)
    state = json.loads(path.read_bytes())
    assert state["schema_version"] == 1 and state["migration"] is None
    assert datetime.fromisoformat(state["records"]["existing"]["created_at"]) == first.created_at


async def test_duplicate_uuid_aliases_are_corruption_not_two_active_memories(tmp_path):
    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    await migration.run()
    path = tmp_path / "user" / "memory.json"
    data = json.loads(path.read_bytes())
    data["records"][str(UUID(record.memory_id))] = data["records"][record.memory_id]
    path.write_text(json.dumps(data))
    original = path.read_bytes()
    with pytest.raises(StorageError) as error:
        await store.aget(USER, record.memory_id)
    assert error.value.code == "memory_corrupt" and path.read_bytes() == original


async def test_cancelled_migration_collects_disk_operation_before_returning(tmp_path, monkeypatch):
    import asyncio
    import threading

    import pycode.adapters.storage.json_memory as module

    legacy, store, migration, _, _ = setup(tmp_path)
    record = await seed(legacy)
    started, release = threading.Event(), threading.Event()
    original = module.atomic_write

    def delayed(path, data):
        if path.name == "memory.json":
            started.set()
            assert release.wait(3)
        return original(path, data)

    monkeypatch.setattr(module, "atomic_write", delayed)
    task = asyncio.create_task(migration.run())
    try:
        assert await asyncio.to_thread(started.wait, 2)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)
    assert store.read(USER)["revision"] == 1
    assert json.loads((store.roots[USER] / "migration.json").read_bytes())["status"] == "complete"
    await migration.run()
    assert (
        len(await store.asearch(USER)) == 1
        and (await store.aget(USER, record.memory_id)).key == record.memory_id
    )
