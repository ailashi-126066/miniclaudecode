"""JSON memory scopes, framework operations, publication and crash boundaries."""

import asyncio
import json
from copy import deepcopy
from uuid import NAMESPACE_DNS, uuid4, uuid5

import pytest
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.store.base import GetOp, InvalidNamespaceError, PutOp, SearchOp
from langmem import (
    create_manage_memory_tool,
    create_memory_store_manager,
    create_search_memory_tool,
)
from tests.support.model import ScriptedChatModel

from pycode.adapters.storage.json_memory import JsonMemoryStore
from pycode.core.errors import StorageError, ToolError
from pycode.core.memory_schema import MemoryValue, SemanticMemory

USER = ("memories", "user", "u")
PROJECT = ("memories", "project", "p")


def value(body="用最小示例解释参数传递。", *, kind="SemanticMemory"):
    content = {
        "title": "讲解偏好",
        "summary": "喜欢最小示例",
        "body": body,
        "sources": [{"session_id": "s", "entry_id": "e", "quote": "解释参数怎么传"}],
    }
    if kind == "SemanticMemory":
        content["category"] = "user_preference"
    elif kind == "EpisodicMemory":
        content.update(
            situation="参数错误",
            actions=["打印参数"],
            outcome="断言通过",
            applicability=["参数调试"],
            verification="observed",
        )
    else:
        content.update(applicability=["代码讲解"], origin="user_explicit")
    return {"kind": kind, "content": content}


class Selector:
    def __init__(self):
        self.calls = []
        self.choices = None
        self.failure = None

    async def __call__(self, query, directory):
        self.calls.append((query, deepcopy(directory)))
        if self.failure:
            raise self.failure
        if self.choices is not None:
            return self.choices
        return [{"id": e["id"], "score": 0.8} for e in reversed(directory)]


def make_store(tmp_path, **options):
    selector = Selector()
    checks = []

    def validate(namespace, key, new, old):
        checks.append((namespace, key, new, old))

    store = JsonMemoryStore(
        {USER: tmp_path / "user", PROJECT: tmp_path / "project"},
        selector=selector,
        validate_change=validate,
        **options,
    )
    return store, selector, checks


def progress():
    return {
        "session_id": "s",
        "branch_id": "b",
        "entry_ids": ["e"],
        "prefix_hash": "a" * 64,
        "start": {"entry_id": "e", "offset": 0},
        "end": {"entry_id": "e", "offset": 200},
    }


@pytest.mark.parametrize("kind", ["SemanticMemory", "EpisodicMemory", "ProceduralMemory"])
def test_three_kinds_and_code_round_trip_one_file(tmp_path, kind):
    store, _, checks = make_store(tmp_path)
    key = str(uuid4())
    body = "```python\ndef f(x):\n    return x + 1\n```\n参数由调用方传入。"
    store.put(USER, key, value(body, kind=kind))
    result = store.get(USER, key)
    assert result.key == key and result.namespace == USER
    assert result.value["kind"] == kind and result.value["content"]["body"] == body
    assert result.created_at == result.updated_at and result.created_at.tzinfo
    assert {p.name for p in (tmp_path / "user").iterdir()} == {"memory.lock", "memory.json"}
    assert len(checks) == 1
    result.value["content"]["body"] = "mutated"
    assert store.get(USER, key).value["content"]["body"] == body


async def test_async_batch_order_stable_ids_times_and_reopen(tmp_path):
    store, selector, _ = make_store(tmp_path)
    results = await store.abatch(
        [GetOp(USER, "same"), PutOp(USER, "same", value()), GetOp(USER, "same"), SearchOp(USER)]
    )
    assert results[0:2] == [None, None] and results[2].key == results[3][0].key == "same"
    first = results[2]
    await store.aput(USER, "same", value("以后更简短。"))
    updated = await store.aget(USER, "same")
    assert updated.created_at == first.created_at and updated.updated_at >= first.updated_at
    revision = store.read(USER)["revision"]
    await store.aput(USER, "same", updated.value)
    assert store.read(USER)["revision"] == revision
    reopened, _, _ = make_store(tmp_path)
    assert await reopened.aget(USER, "same") == updated
    assert not selector.calls


def test_same_key_in_distinct_scopes_and_namespace_paging(tmp_path):
    store, _, _ = make_store(tmp_path)
    store.put(USER, "same", value("用户规则"))
    store.put(PROJECT, "same", value("项目规则"))
    assert store.get(USER, "same").value != store.get(PROJECT, "same").value
    assert store.list_namespaces(prefix=("memories", "*"), suffix=("u",)) == [USER]
    assert store.list_namespaces(max_depth=1) == [("memories",)]
    assert store.list_namespaces(offset=1, limit=1) == [USER]
    assert [i.namespace for i in store.search((), offset=1, limit=1)] == [USER]
    with pytest.raises(ToolError, match="outside"):
        store.get(("memories", "project", "other"), "same")
    with pytest.raises(InvalidNamespaceError):
        store.put(USER + ("..",), "same", value())
    assert not (tmp_path / "other").exists()


@pytest.mark.parametrize(
    "filter_,count",
    [
        ({"kind": "SemanticMemory"}, 2),
        ({"content": {"category": {"$eq": "user_preference"}}}, 2),
        ({"content.title": {"$gte": "讲解偏好", "$lte": "讲解偏好"}}, 2),
        ({"content.title": {"$ne": "讲解偏好"}}, 0),
        ({"content.title": {"$gt": "讲解偏好"}}, 0),
        ({"content.title": {"$lt": "讲解偏好"}}, 0),
        ({"absent": None}, 0),
    ],
)
def test_structured_filters_and_stable_no_model_paging(tmp_path, filter_, count):
    store, selector, _ = make_store(tmp_path)
    store.put(USER, "z", value())
    store.put(USER, "a", value())
    assert len(store.search(USER, filter=filter_)) == count
    assert [i.key for i in store.search(USER, limit=1, offset=1)] == ["z"]
    assert not selector.calls


@pytest.mark.parametrize(
    "filter_",
    [{"kind": {"$contains": "Memory"}}, {"kind": {"$eq": "SemanticMemory", "bad": 1}}, {"": "x"}],
)
def test_unsupported_filters_fail_even_on_empty_store(tmp_path, filter_):
    store, _, _ = make_store(tmp_path)
    with pytest.raises(ToolError) as error:
        store.search(USER, filter=filter_)
    assert error.value.code == "invalid_memory_filter"


@pytest.mark.parametrize("limit,offset", [(-1, 0), (101, 0), (10, -1), (True, 0), (10, 10001)])
async def test_query_paging_budget(tmp_path, limit, offset):
    store, selector, _ = make_store(tmp_path)
    with pytest.raises(ToolError):
        await store.asearch(USER, limit=limit, offset=offset)
    assert not selector.calls


async def test_query_catalog_filter_empty_and_selected_bodies(tmp_path):
    store, selector, _ = make_store(tmp_path)
    assert await store.asearch(USER, query="code") == [] and not selector.calls
    await store.aput(USER, "a", value("完整正文不放入模型目录。"))
    await store.aput(PROJECT, "a", value("另一个作用域。"))
    items = await store.asearch(USER, query="参数传递", filter={"kind": "SemanticMemory"})
    assert [i.key for i in items] == ["a"] and items[0].score == 0.8
    assert "完整正文" not in json.dumps(selector.calls, ensure_ascii=False)
    assert all(json.loads(e["id"])[0] == list(USER) for e in selector.calls[0][1])
    selector.choices = []
    assert await store.asearch(USER, query="irrelevant") == []


@pytest.mark.parametrize(
    "choices",
    [
        None,
        [{"id": "foreign", "score": 1}],
        [{"id": [], "score": 1}],
        [{"id": "foreign", "score": float("nan")}],
        {},
    ],
)
async def test_invalid_model_selection_cannot_read_foreign_body(tmp_path, choices):
    store, selector, _ = make_store(tmp_path)
    await store.aput(USER, "a", value())

    async def choose(query, directory):
        return choices

    store.selector = choose
    with pytest.raises(ToolError) as error:
        await store.asearch(USER, query="choose")
    assert error.value.code == "invalid_memory_selection"


async def test_selector_failure_is_not_an_empty_lookup(tmp_path):
    store, selector, _ = make_store(tmp_path)
    await store.aput(USER, "a", value())
    failure = StorageError("ledger_failed", "cannot settle")
    selector.failure = failure
    with pytest.raises(StorageError) as error:
        await store.asearch(USER, query="choose")
    assert error.value is failure


async def test_all_directory_batches_seen_and_budget_failure_before_model(tmp_path):
    store, selector, _ = make_store(tmp_path, directory_tokens=300, max_queries=16)
    for n in range(9):
        await store.aput(USER, f"id-{n}", value())
    result = await store.asearch(USER, query="解释", limit=9)
    selected = [json.loads(e["id"])[1] for _, d in selector.calls for e in d]
    assert sorted(selected) == [f"id-{n}" for n in range(9)]
    assert len(result) == 9 and len(selector.calls) > 1
    selector.calls.clear()
    store.max_queries = 1
    with pytest.raises(ToolError) as error:
        await store.asearch(USER, query="解释")
    assert error.value.code == "memory_query_budget" and not selector.calls


def test_sync_model_query_outside_loop(tmp_path):
    store, selector, _ = make_store(tmp_path)
    store.put(USER, "a", value())
    assert store.search(USER, query="explain")[0].key == "a"
    assert len(selector.calls) == 1


async def test_sync_model_query_in_loop_requires_async_not_nested_run(tmp_path):
    store, selector, _ = make_store(tmp_path)
    await store.aput(USER, "a", value())
    with pytest.raises(ToolError) as error:
        store.search(USER, query="explain")
    assert error.value.code == "memory_async_required" and not selector.calls


@pytest.mark.parametrize(
    "document",
    [
        b'{"schema_version":1,"schema_version":1}',
        b"broken",
        b'{"schema_version":99,"revision":0,"records":{},"processed_batches":{},"namespace":["memories","user","u"]}',
    ],
)
def test_corrupt_duplicate_or_future_version_never_overwritten(tmp_path, document):
    store, _, _ = make_store(tmp_path)
    root = tmp_path / "user"
    root.mkdir()
    path = root / "memory.json"
    path.write_bytes(document)
    with pytest.raises(StorageError) as error:
        store.put(USER, "a", value())
    assert error.value.code == "memory_corrupt" and path.read_bytes() == document


async def test_nested_duplicates_and_wrong_scope_fail_closed(tmp_path):
    store, _, _ = make_store(tmp_path)
    await store.aput(USER, "a", value())
    path = tmp_path / "user" / "memory.json"
    original = path.read_text()
    path.write_text(
        original.replace(
            '"kind": "SemanticMemory"', '"kind": "EpisodicMemory", "kind": "SemanticMemory"'
        )
    )
    with pytest.raises(StorageError):
        await store.aget(USER, "a")
    data = json.loads(original)
    data["namespace"] = list(PROJECT)
    path.write_text(json.dumps(data))
    with pytest.raises(StorageError) as error:
        await store.aget(USER, "a")
    assert error.value.code == "memory_scope_denied"


async def test_staged_reads_concurrent_writes_and_separate_scope_commits(tmp_path):
    store, _, checks = make_store(tmp_path)
    view = await store.open_update()
    await asyncio.gather(
        *(view.aput(USER, f"k-{i}", value()) for i in range(8)), view.aput(PROJECT, "p", value())
    )
    assert await store.aget(USER, "k-0") is None
    assert len(await view.asearch(USER)) == 8 and not checks
    await view.commit(USER, batch_id="batch-u", progress=progress())
    assert len(checks) == 8 and len(await store.asearch(USER)) == 8
    with pytest.raises(StorageError):
        await view.aput(PROJECT, "late", value())
    await view.commit(PROJECT, batch_id="batch-p", progress=progress())
    assert await store.aget(PROJECT, "p")
    assert store.read(USER)["processed_batches"]["batch-u"]["end"]["offset"] == 200


async def test_revision_conflict_never_clobbers_active_change(tmp_path):
    store, _, _ = make_store(tmp_path)
    await store.aput(USER, "a", value())
    view = await store.open_update()
    await view.aput(USER, "a", value("过期后台修改"))
    await store.aput(USER, "a", value("新的主动修正"))
    with pytest.raises(StorageError) as error:
        await view.commit(USER, batch_id="b", progress=progress())
    assert error.value.code == "memory_revision_conflict"
    assert (await store.aget(USER, "a")).value["content"]["body"] == "新的主动修正"
    assert not store.read(USER)["processed_batches"]


async def test_committed_batch_replay_does_not_undo_later_correction(tmp_path):
    store, _, _ = make_store(tmp_path)
    view = await store.open_update()
    await view.aput(USER, "a", value())
    await view.commit(USER, batch_id="batch", progress=progress())
    await store.aput(USER, "a", value("后续修正"))
    replay = await store.open_update()
    await replay.aput(USER, "a", value())
    await replay.commit(USER, batch_id="batch", progress=progress())
    assert (await store.aget(USER, "a")).value["content"]["body"] == "后续修正"
    conflict = await store.open_update()
    await conflict.aput(USER, "a", value("陌生修改"))
    with pytest.raises(StorageError) as error:
        await conflict.commit(USER, batch_id="batch", progress=progress())
    assert error.value.code == "memory_batch_conflict"


async def test_validator_rejects_whole_associated_group_without_progress(tmp_path):
    store, _, _ = make_store(tmp_path)

    def validate(namespace, key, new, old):
        if key == "invalid":
            raise ToolError("memory_evidence_invalid", "unsupported conclusion")

    store.validate_change = validate
    view = await store.open_update()
    await view.aput(USER, "valid", value())
    await view.aput(USER, "invalid", value())
    with pytest.raises(ToolError):
        await view.commit(USER, batch_id="batch", progress=progress())
    assert await store.aget(USER, "valid") is None
    assert store.read(USER)["revision"] == 0 and not store.read(USER)["processed_batches"]


async def test_no_change_batch_progress_and_discard(tmp_path):
    store, _, _ = make_store(tmp_path)
    view = await store.open_update()
    await view.commit(USER, batch_id="empty", progress=progress())
    assert store.read(USER)["revision"] == 1 and not store.read(USER)["records"]
    view = await store.open_update()
    await view.aput(USER, "x", value())
    view.discard()
    with pytest.raises(StorageError):
        await view.commit(USER)
    assert await store.aget(USER, "x") is None


async def test_delete_backup_and_reopening_retains_recovery_data(tmp_path):
    store, _, checks = make_store(tmp_path)
    await store.aput(USER, "a", value())
    await store.adelete(USER, "a")
    reopened, _, _ = make_store(tmp_path)
    assert await reopened.aget(USER, "a") is None
    backup = json.loads((tmp_path / "user" / "versions" / "revision-1.json").read_bytes())
    assert backup["records"]["a"]["value"]["content"]["body"] == value()["content"]["body"]
    assert checks[-1][2] is None and checks[-1][3]["value"]["kind"] == "SemanticMemory"


async def test_failed_atomic_replace_keeps_active_snapshot_and_unprocessed_batch(
    tmp_path, monkeypatch
):
    store, _, _ = make_store(tmp_path)
    await store.aput(USER, "a", value())
    original = (tmp_path / "user" / "memory.json").read_bytes()
    import pycode.adapters.tools.backend as backend

    replace = backend.os.replace

    def fail(source, target):
        if target.name == "memory.json":
            raise OSError("disk failure")
        replace(source, target)

    monkeypatch.setattr(backend.os, "replace", fail)
    view = await store.open_update()
    await view.aput(USER, "a", value("未提交"))
    with pytest.raises(StorageError) as error:
        await view.commit(USER, batch_id="failed", progress=progress())
    assert error.value.code == "memory_io_error"
    assert (tmp_path / "user" / "memory.json").read_bytes() == original
    assert not list((tmp_path / "user").glob("*.partial"))
    assert not store.read(USER)["processed_batches"]


@pytest.mark.parametrize("option", [{"ttl": 1}, {"index": ["content.body"]}])
def test_unsupported_capabilities_explicitly_rejected(tmp_path, option):
    store, _, _ = make_store(tmp_path)
    with pytest.raises((ToolError, NotImplementedError)):
        store.put(USER, "a", value(), **option)
    assert store.get(USER, "a") is None


async def test_kind_change_and_invalid_schema_cannot_publish(tmp_path):
    store, _, _ = make_store(tmp_path)
    await store.aput(USER, "a", value())
    with pytest.raises(ToolError) as error:
        await store.aput(USER, "a", value(kind="ProceduralMemory"))
    assert error.value.code == "memory_kind_conflict"
    invalid = value()
    invalid["content"]["sources"] = []
    with pytest.raises(ToolError):
        await store.aput(USER, "invalid", invalid)
    secret = value("api_key=abcdefghijklmnop")
    with pytest.raises(ToolError):
        await store.aput(USER, "secret", secret)


async def test_real_langmem_manage_search_and_manager_round_trip(tmp_path):
    store, selector, _ = make_store(tmp_path)
    view = await store.open_update()
    manage = create_manage_memory_tool(namespace=USER, schema=MemoryValue, store=view)
    created = await manage.ainvoke({"action": "create", "content": value()})
    key = created.rsplit(" ", 1)[-1]
    assert (await view.aget(USER, key)).value["kind"] == "SemanticMemory"
    assert await store.aget(USER, key) is None
    search = create_search_memory_tool(namespace=USER, store=view)
    assert key in await search.ainvoke({"query": "参数"})
    # Real native manager uses the same kind/content object and updates the original ID.
    model = ScriptedChatModel(
        responses=[
            AIMessage(
                content="",
                tool_calls=[
                    {
                        "name": "PatchDoc",
                        "id": "schema-call",
                        "args": {
                            "json_doc_id": uuid5(NAMESPACE_DNS, str((*USER, key))).hex,
                            "planned_edits": "Update the supported behavior rule.",
                            "patches": [
                                {
                                    "op": "replace",
                                    "path": "/body",
                                    "value": "以后简短说明参数来源。",
                                }
                            ],
                        },
                    }
                ],
            )
        ]
    )
    manager = create_memory_store_manager(
        model,
        schemas=[SemanticMemory],
        namespace=USER,
        store=view,
        query_limit=4,
        enable_deletes=True,
    )
    await manager.ainvoke(
        {"messages": [HumanMessage(content="以后简短说明参数来源。")], "max_steps": 1}
    )
    assert (await view.aget(USER, key)).value["content"]["body"] == "以后简短说明参数来源。"
    assert len(await view.asearch(USER)) == 1 and selector.calls
    await view.commit(USER)
    committed = await store.aget(USER, key)
    assert committed.value["content"]["body"] == "以后简短说明参数来源。"
    delete = create_manage_memory_tool(namespace=USER, schema=MemoryValue, store=store)
    await delete.ainvoke({"action": "delete", "id": key})
    assert await store.aget(USER, key) is None


async def test_scopes_commit_independently_when_one_evidence_group_fails(tmp_path):
    store, _, _ = make_store(tmp_path)

    def validate(namespace, key, new, old):
        if namespace == USER:
            raise ToolError("memory_evidence_invalid", "unsupported user record")

    store.validate_change = validate
    view = await store.open_update()
    await view.aput(USER, "bad", value())
    await view.aput(PROJECT, "good", value())
    with pytest.raises(ToolError):
        await view.commit(USER, batch_id="u-batch", progress=progress())
    await view.commit(PROJECT, batch_id="p-batch", progress=progress())
    assert not store.read(USER)["processed_batches"] and not store.read(USER)["records"]
    assert (await store.aget(PROJECT, "good")) and "p-batch" in store.read(PROJECT)[
        "processed_batches"
    ]


async def test_cancellation_waits_for_publication_and_replay_uses_receipt(tmp_path, monkeypatch):
    import threading

    import pycode.adapters.storage.json_memory as storage

    store, _, _ = make_store(tmp_path)
    started, release = threading.Event(), threading.Event()
    original = storage.atomic_write

    def delayed(path, data):
        if path.name == "memory.json":
            started.set()
            assert release.wait(3)
        original(path, data)

    monkeypatch.setattr(storage, "atomic_write", delayed)
    view = await store.open_update()
    await view.aput(USER, "a", value())
    task = asyncio.create_task(view.commit(USER, batch_id="batch", progress=progress()))
    try:
        assert await asyncio.to_thread(started.wait, 2)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done() and not (tmp_path / "user" / "memory.json").exists()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)
    assert store.read(USER)["revision"] == 1 and "batch" in store.read(USER)["processed_batches"]
    replay = await store.open_update()
    await replay.aput(USER, "a", value())
    await replay.commit(USER, batch_id="batch", progress=progress())
    assert store.read(USER)["revision"] == 1 and len(await store.asearch(USER)) == 1


async def test_return_body_budget_is_not_a_silent_partial_result(tmp_path):
    store, selector, _ = make_store(tmp_path)
    for i in range(5):
        await store.aput(USER, str(i), value("字" * 16000))
    with pytest.raises(ToolError) as error:
        await store.asearch(USER, query="all", limit=5)
    assert error.value.code == "memory_query_budget"
    assert len(selector.calls) == 1 and len(store.read(USER)["records"]) == 5


@pytest.mark.parametrize("path_name", ["memory.json", "memory.lock", "versions"])
def test_symlink_authority_paths_never_write_target(tmp_path, path_name):
    store, _, _ = make_store(tmp_path)
    root = tmp_path / "user"
    root.mkdir()
    target = tmp_path / "outside"
    target.write_bytes(b"untouched")
    (root / path_name).symlink_to(target)
    with pytest.raises(StorageError) as error:
        store.put(USER, "a", value())
    assert error.value.code == "memory_path_invalid" and target.read_bytes() == b"untouched"


@pytest.mark.parametrize(
    "mutation", ["missing_revision", "bool_version", "naive_time", "bad_checksum", "backward_time"]
)
def test_structural_corruption_preserves_original_bytes(tmp_path, mutation):
    store, _, _ = make_store(tmp_path)
    store.put(USER, "a", value())
    path = tmp_path / "user" / "memory.json"
    data = json.loads(path.read_bytes())
    if mutation == "missing_revision":
        del data["revision"]
    elif mutation == "bool_version":
        data["schema_version"] = True
    elif mutation == "naive_time":
        data["records"]["a"]["created_at"] = "2026-01-01T00:00:00"
    elif mutation == "bad_checksum":
        data["records"]["a"]["value"]["content"]["sources"][0]["sha256"] = "b" * 64
    else:
        data["records"]["a"]["updated_at"] = "2020-01-01T00:00:00+00:00"
    damaged = json.dumps(data).encode()
    path.write_bytes(damaged)
    with pytest.raises(StorageError) as error:
        store.put(USER, "a", value("must not overwrite"))
    assert error.value.code == "memory_corrupt" and path.read_bytes() == damaged


async def test_invalid_progress_keeps_view_mutable_until_a_valid_freeze(tmp_path):
    store, _, _ = make_store(tmp_path)
    view = await store.open_update()
    await view.aput(USER, "first", value())
    invalid = progress()
    invalid["end"]["entry_id"] = "foreign"
    with pytest.raises(ToolError) as error:
        await view.commit(USER, batch_id="batch", progress=invalid)
    assert error.value.code == "invalid_memory_progress"
    await view.aput(USER, "second", value())
    await view.commit(USER, batch_id="batch", progress=progress())
    assert [i.key for i in await store.asearch(USER)] == ["first", "second"]
    assert store.read(USER)["revision"] == 1
