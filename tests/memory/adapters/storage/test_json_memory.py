"""One atomic memory.json per scope behind the LangGraph BaseStore and the write filter."""

import json

import pytest

from pycode.kernel.domain.errors import StorageError, ToolError
from pycode.memory.adapters.storage.json_memory import JsonMemoryStore, MemoryChangeLog

USER = ("memories", "user", "u1")
PROJECT = ("memories", "project", "p1")


def make_store(tmp_path):
    return JsonMemoryStore({USER: tmp_path / "user", PROJECT: tmp_path / "project"})


def text(content):
    return {"kind": "Memory", "content": {"content": content}}


async def test_put_get_delete_and_one_atomic_file_per_scope(tmp_path):
    store = make_store(tmp_path)
    await store.aput(USER, "k1", {"content": "Prefers pytest over unittest."})
    await store.aput(PROJECT, "k2", text("Builds with hatch."))
    item = await store.aget(USER, "k1")
    assert item.value == text("Prefers pytest over unittest.")
    user_file = json.loads((tmp_path / "user" / "memory.json").read_text())
    assert user_file["namespace"] == list(USER) and set(user_file["records"]) == {"k1"}
    assert set(json.loads((tmp_path / "project" / "memory.json").read_text())["records"]) == {"k2"}
    assert not list((tmp_path / "user").glob("*.partial"))
    await store.adelete(USER, "k1")
    assert await store.aget(USER, "k1") is None


async def test_text_search_namespace_prefix_and_filters(tmp_path):
    store = make_store(tmp_path)
    await store.aput(USER, "a", text("Use pytest fixtures for database tests."))
    await store.aput(USER, "b", text("Prefers dark terminal themes."))
    await store.aput(PROJECT, "c", text("pytest runs with asyncio auto mode."))
    found = await store.asearch(("memories",), query="pytest database")
    assert [item.key for item in found][:2] == ["a", "c"]
    assert found[0].score > found[1].score
    assert [item.key for item in await store.asearch(USER, query="themes")] == ["b"]
    assert {item.key for item in await store.asearch(("memories", "user"))} == {"a", "b"}
    filtered = await store.asearch((), filter={"kind": "Memory"}, limit=1)
    assert len(filtered) == 1
    assert await store.asearch((), query="nothing-matches-here") == []
    with pytest.raises(ToolError) as error:
        await store.aput(("memories", "other", "x"), "k", text("x"))
    assert error.value.code == "memory_scope_denied"


async def test_write_filter_rejects_secrets_length_and_capacity(tmp_path, monkeypatch):
    store = make_store(tmp_path)
    for secret in (
        "my key is sk-abcdefghijklmnopqrstuv",
        "api_key: hunter22",
        "PASSWORD=xyz",
        "aws AKIAABCDEFGHIJKLMNOP",
    ):
        with pytest.raises(ToolError) as error:
            await store.aput(USER, "s", text(secret))
        assert error.value.code == "memory_secret_rejected"
    with pytest.raises(ToolError) as error:
        await store.aput(USER, "long", text("x" * 2001))
    assert error.value.code == "memory_entry_too_long"
    monkeypatch.setattr("pycode.memory.domain.memory.MAX_ENTRIES", 2)
    await store.aput(USER, "one", text("first"))
    await store.aput(USER, "two", text("second"))
    await store.aput(USER, "two", text("second, updated"))
    with pytest.raises(ToolError) as error:
        await store.aput(USER, "three", text("third"))
    assert error.value.code == "memory_limit_reached"
    assert set(store.read(USER)["records"]) == {"one", "two"}


async def test_recording_view_logs_changes_and_can_skip_rejected(tmp_path):
    store = make_store(tmp_path)
    await store.aput(USER, "k", text("old"))
    view = store.recording(skip_rejected=True)
    await view.aput(USER, "k", text("new"))
    await view.aput(USER, "bad", text("token = abc"))
    await view.adelete(USER, "k")
    assert [(c["key"], c["before"] is None, c["after"] is None) for c in view.changes] == [
        ("k", False, False),
        ("k", False, True),
    ]
    assert view.rejected == [
        {"namespace": list(USER), "key": "bad", "reason": "memory_secret_rejected"}
    ]
    before = view.changes[0]["before"]
    await store.apply(USER, {"k": before}, restore=True)
    assert store.read(USER)["records"]["k"] == before


@pytest.mark.parametrize(
    "removed_field",
    ["migration", "processed_batches", "group_receipts", "tool_receipts", "move_receipts"],
)
async def test_old_memory_fields_are_rejected_without_conversion(tmp_path, removed_field):
    root = tmp_path / "user"
    root.mkdir()
    old = {
        "schema_version": 1,
        "namespace": list(USER),
        "revision": 3,
        "records": {},
        removed_field: {},
    }
    path = root / "memory.json"
    path.write_text(json.dumps(old))
    before = path.read_bytes()
    store = make_store(tmp_path)
    with pytest.raises(StorageError) as error:
        await store.asearch(USER)
    assert error.value.code == "memory_corrupt"
    with pytest.raises(StorageError):
        await store.aput(USER, "new", text("new memory"))
    assert path.read_bytes() == before


async def test_mode_settings_and_change_log(tmp_path):
    store = make_store(tmp_path)
    assert await store.get_mode(USER) == "auto"
    await store.set_mode(USER, "off")
    assert await store.get_mode(USER) == "off"
    log = MemoryChangeLog(tmp_path / "user" / "changes.jsonl")
    assert await log.read() == []
    await log.append({"id": "1", "origin": "consolidate", "changes": []})
    with (tmp_path / "user" / "changes.jsonl").open("ab") as output:
        output.write(b'{"partial":')
    assert await log.read() == [{"id": "1", "origin": "consolidate", "changes": []}]

    await log.append({"id": "2", "origin": "edit", "changes": []})
    assert [entry["id"] for entry in await log.read()] == ["1", "2"]


async def test_undo_checks_every_scope_before_restoring_anything(tmp_path):
    store = make_store(tmp_path)
    view = store.recording()
    await view.aput(USER, "u", text("user memory"))
    await view.aput(PROJECT, "p", text("project memory"))
    changes = view.changes
    # Another session modifies one of the consolidated entries before undo gets the leases.
    other = make_store(tmp_path)
    await other.aput(PROJECT, "p", text("changed concurrently"))
    with pytest.raises(StorageError) as error:
        await store.recording().restore(changes)
    assert error.value.code == "memory_undo_conflict"
    assert (await store.aget(USER, "u")).value == text("user memory")
    assert (await store.aget(PROJECT, "p")).value == text("changed concurrently")


@pytest.mark.parametrize(
    "change", [{"revision": "3"}, {"schema_version": 0}, {"schema_version": True}]
)
async def test_memory_snapshot_does_not_coerce_old_values(tmp_path, change):
    root = tmp_path / "user"
    root.mkdir()
    path = root / "memory.json"
    path.write_text(
        json.dumps(
            {"schema_version": 1, "namespace": list(USER), "revision": 0, "records": {}, **change}
        )
    )
    original = path.read_bytes()
    with pytest.raises(StorageError):
        await make_store(tmp_path).snapshot(USER)
    assert path.read_bytes() == original


async def test_removed_review_mode_is_not_converted_to_auto(tmp_path):
    root = tmp_path / "user"
    root.mkdir()
    path = root / "settings.json"
    path.write_text('{"mode": "review"}')
    with pytest.raises(StorageError):
        await make_store(tmp_path).get_mode(USER)
    assert path.read_text() == '{"mode": "review"}'


async def test_memory_ids_are_exact_without_uuid_alias_lookup(tmp_path):
    store = make_store(tmp_path)
    key = "0123456789abcdef0123456789abcdef"
    await store.aput(USER, key, text("exact key"))
    assert await store.aget(USER, "01234567-89ab-cdef-0123-456789abcdef") is None
    assert (await store.aget(USER, key)).value == text("exact key")
