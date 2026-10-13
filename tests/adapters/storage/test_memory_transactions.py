"""Recovery finishes a prepared record after a real partial two-file publication."""

import json
import os
from dataclasses import replace
from uuid import uuid4

from pycode.adapters.storage.memory import FileMemoryStore
from pycode.core.memory import MemoryRecord, content_hash
from pycode.core.values import plain_data


async def test_memory_prepared_transaction_recovery_and_version_backup(tmp_path):
    user = tmp_path / "user"
    store = FileMemoryStore(user, tmp_path / "project")
    record = MemoryRecord(uuid4().hex, "tests", "user_preference", "user", "Use pytest.", 1, 1)
    await store.write(record)
    current = await store.read_record(record.memory_id)
    updated = replace(current, content="Use pytest and plain functions.", updated_at=2)
    await store.write(updated, expected_hash=current.sha256)
    versions = list((user / "versions").glob("*/content.md"))
    assert len(versions) == 1 and versions[0].read_text() == "Use pytest."
    pending = user / "transactions" / uuid4().hex
    pending.mkdir()
    content = "Use pytest with clear functions."
    data = plain_data(updated)
    data.pop("content")
    data["sha256"] = content_hash(content)
    data["summary"] = content
    staged_content = pending / "content.md"
    staged_content.write_bytes(content.encode())
    (pending / "record.json").write_bytes(json.dumps(data).encode())
    (pending / "manifest.json").write_bytes(
        json.dumps(
            {
                "memory_id": current.memory_id,
                "sha256": data["sha256"],
                "status": "prepared",
            }
        ).encode()
    )
    # Simulate process death between the same two os.replace calls.
    os.replace(staged_content, user / (current.memory_id + ".md"))
    reopened = FileMemoryStore(user, tmp_path / "project")
    restored = await reopened.read_record(current.memory_id)
    assert restored.content == content and restored.sha256 == data["sha256"]
    assert json.loads((pending / "manifest.json").read_bytes())["status"] == "complete"
    assert (await reopened.list_records())[0] == restored


async def test_persisted_memory_mode_survives_new_service(tmp_path):
    store = FileMemoryStore(tmp_path / "user", tmp_path / "project")
    await store.set_mode("review")
    reopened = FileMemoryStore(tmp_path / "user", tmp_path / "project")
    assert await reopened.get_mode() == "review"


async def test_memory_removal_recovers_after_first_file_move(tmp_path):
    user = tmp_path / "user"
    store = FileMemoryStore(user, tmp_path / "project")
    record = MemoryRecord(uuid4().hex, "tests", "user_preference", "user", "Use pytest.", 1, 1)
    await store.write(record)
    current = await store.read_record(record.memory_id)
    pending = user / "transactions" / uuid4().hex
    pending.mkdir()
    (pending / "manifest.json").write_text(
        json.dumps(
            {
                "action": "remove",
                "memory_id": current.memory_id,
                "sha256": current.sha256,
                "status": "prepared",
            }
        )
    )
    archived = user / "removed" / current.memory_id
    archived.mkdir(parents=True)
    os.replace(user / (current.memory_id + ".md"), archived / "content.md")
    reopened = FileMemoryStore(user, tmp_path / "project")
    assert await reopened.list_records() == ()
    assert (archived / "record.json").is_file()
    assert (archived / "content.md").read_text() == "Use pytest."
    assert json.loads((pending / "manifest.json").read_text())["status"] == "complete"
