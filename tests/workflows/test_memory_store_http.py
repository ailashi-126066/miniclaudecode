"""Public SDK, official LangMem and JSON Store share actual per-call accounting."""

import json
from dataclasses import replace
from uuid import NAMESPACE_DNS, uuid5

import pytest
from langchain_core.messages import HumanMessage
from langmem import create_memory_store_manager
from tests.adapters.storage.test_json_memory import USER, make_store, value
from tests.support.execution import provider
from tests.support.http_provider import anthropic_message, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_background_model
from pycode.core.errors import ModelError, ToolError
from pycode.core.memory_schema import SemanticMemory
from pycode.core.requests import RetryPolicy
from pycode.workflows.memory_query import MemoryDirectorySelector
from pycode.workflows.metered_model import MeteredChatModel


def facade(owner, prefix):
    return MeteredChatModel(
        model=owner.model, requests=owner.requests, logical_prefix=prefix, input_limit=20000
    )


def reply(request, arguments, *, kind="openai"):
    if kind == "anthropic":
        response = anthropic_message(tool=True)
        response.body = (
            response.body.decode()
            .replace('"name": "echo"', '"name": "MemoryDirectoryChoices"')
            .replace(json.dumps('{"value":"wire"}'), json.dumps(json.dumps(arguments)))
            .encode()
        )
        return response
    return skill_reply("MemoryDirectoryChoices", arguments, "directory-choice")


@pytest.mark.parametrize("kind", ["openai", "deepseek", "anthropic"])
async def test_real_sdk_directory_selection_schema_and_each_request_ledger(
    tmp_path, monkeypatch, kind
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    identifier = json.dumps([USER, "first"])
    with protocol_server(
        lambda request: reply(
            request, {"selections": [{"id": identifier, "score": 0.9}]}, kind=kind
        )
    ) as (url, captured):
        configured = replace(
            provider(url),
            implementation=kind,
            openai_api="chat_completions" if kind == "openai" else None,
        )
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            configured,
            (),
            "s",
            "job",
            "memory_extract",
            retry_policy=RetryPolicy(max_retries=0),
        )
        try:
            store, _, _ = make_store(tmp_path)
            store.selector = MemoryDirectorySelector(facade(owner, "memory_directory"))
            await store.aput(USER, "first", value("正文不进入目录模型提示。"))
            await store.aput(USER, "second", value("未选择的完整正文。"))
            selected = await store.asearch(USER, query="参数来源")
            assert [i.key for i in selected] == ["first"] and selected[0].score == 0.9
            assert len(captured) == 1
            assert "正文不进入" not in json.dumps(captured[0]["body"], ensure_ascii=False)
            assert "未选择的完整正文" not in json.dumps(captured[0]["body"], ensure_ascii=False)
            records = await owner.records.list_requests("s")
            assert len(records) == 1 and records[0].status == "completed"
            assert records[0].logical_id == "memory_directory:0"
            assert records[0].usage.input_tokens == 20 and records[0].usage.output_tokens == 5
            assert (
                records[0].provider == configured.name
                and records[0].model_id == configured.model_id
            )
        finally:
            await owner.aclose()


async def test_native_store_manager_schema_requests_each_metered_and_staged(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")

    def respond(request):
        tools = request["body"]["tools"]
        if tools[0]["function"]["name"] == "MemoryDirectoryChoices":
            return reply(
                request, {"selections": [{"id": json.dumps([USER, "original"]), "score": 0.95}]}
            )
        assert {"PatchDoc", "SemanticMemory"} <= {tool["function"]["name"] for tool in tools}
        return skill_reply(
            "PatchDoc",
            {
                "json_doc_id": uuid5(NAMESPACE_DNS, str((*USER, "original"))).hex,
                "planned_edits": "Apply the lasting user correction.",
                "patches": [
                    {"op": "replace", "path": "/body", "value": "以后给最小示例并解释参数来源。"}
                ],
            },
            "memory-update",
        )

    with protocol_server(respond) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "job",
            "memory_extract",
            retry_policy=RetryPolicy(max_retries=0),
        )
        try:
            store, _, _ = make_store(tmp_path)
            store.selector = MemoryDirectorySelector(facade(owner, "memory_directory"))
            await store.aput(USER, "original", value())
            view = await store.open_update()
            manager = create_memory_store_manager(
                facade(owner, "memory_manager"),
                schemas=[SemanticMemory],
                namespace=USER,
                store=view,
                query_limit=4,
            )
            await manager.ainvoke(
                {
                    "messages": [HumanMessage(content="以后给最小示例并解释参数来源。")],
                    "max_steps": 1,
                }
            )
            assert (await store.aget(USER, "original")).value["content"]["body"] == value()[
                "content"
            ]["body"]
            assert (await view.aget(USER, "original")).value["content"][
                "body"
            ] == "以后给最小示例并解释参数来源。"
            await view.commit(USER)
            assert len(await store.asearch(USER)) == 1
            records = await owner.records.list_requests("s")
            assert len(captured) == len(records) == 2
            assert {r.logical_id for r in records} == {"memory_directory:0", "memory_manager:0"}
            assert all(r.status == "completed" and r.usage.total_tokens == 25 for r in records)
            assert sum(r.usage.total_tokens for r in records) == 50
        finally:
            await owner.aclose()


@pytest.mark.parametrize("case", ["unknown", "malformed", "partial"])
async def test_failed_selection_records_usage_but_no_empty_lookup_or_saved_progress(
    tmp_path, monkeypatch, case
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")

    def respond(request):
        arguments = (
            {"selections": [{"id": "foreign", "score": 0.9}]}
            if case == "unknown"
            else {"selections": [{"id": "foreign", "score": 2}]}
        )
        response = reply(request, arguments)
        if case == "partial":
            response.body = response.body.replace(
                b'"finish_reason": "tool_calls"', b'"finish_reason": "length"'
            )
        return response

    with protocol_server(respond) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "job",
            "memory_extract",
            retry_policy=RetryPolicy(max_retries=0),
        )
        try:
            store, _, _ = make_store(tmp_path)
            store.selector = MemoryDirectorySelector(facade(owner, "memory_directory"))
            await store.aput(USER, "original", value())
            with pytest.raises((ToolError, ModelError)):
                await store.asearch(USER, query="parameters")
            assert len(captured) == 1
            assert store.read(USER)["revision"] == 1 and not store.read(USER)["processed_batches"]
            record = (await owner.records.list_requests("s"))[0]
            assert record.status == "completed" and record.usage.total_tokens == 25
        finally:
            await owner.aclose()


async def test_concurrent_native_queries_share_runner_without_observation_or_usage_overlap(
    tmp_path, monkeypatch
):
    import asyncio
    import threading

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    started, release = threading.Event(), threading.Event()

    def respond(request):
        body = str(request["body"]["messages"])
        response = reply(request, {"selections": [{"id": json.dumps([USER, "a"]), "score": 0.9}]})
        if "first-query" in body:
            response.started, response.release = started, release
        else:
            response.body = response.body.replace(
                b'"prompt_tokens": 20', b'"prompt_tokens": 47'
            ).replace(b'"total_tokens": 25', b'"total_tokens": 52')
        return response

    with protocol_server(respond) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "job",
            "memory_extract",
            retry_policy=RetryPolicy(max_retries=0),
        )
        tasks = []
        try:
            store, _, _ = make_store(tmp_path)
            store.selector = MemoryDirectorySelector(facade(owner, "memory_directory"))
            await store.aput(USER, "a", value())
            tasks.append(asyncio.create_task(store.asearch(USER, query="first-query")))
            assert await asyncio.to_thread(started.wait, 2)
            second_entered = asyncio.Event()
            original_invoke = owner.requests.invoke

            async def tracked_invoke(request, handler, *, logical_id=None):
                if "second-query" in str(request.messages):
                    second_entered.set()
                return await original_invoke(request, handler, logical_id=logical_id)

            owner.requests.invoke = tracked_invoke
            tasks.append(asyncio.create_task(store.asearch(USER, query="second-query")))
            await asyncio.wait_for(second_entered.wait(), 2)
            assert len(captured) == 1 and len(await owner.records.list_requests("s")) == 1
            release.set()
            assert all(result[0].key == "a" for result in await asyncio.gather(*tasks))
            records = await owner.records.list_requests("s")
            assert len(captured) == len(records) == 2
            assert sorted(r.usage.input_tokens for r in records) == [20, 47]
            assert sorted(r.usage.total_tokens for r in records) == [25, 52]
            assert len({r.logical_id for r in records}) == 2
        finally:
            release.set()
            await asyncio.gather(*tasks, return_exceptions=True)
            await owner.aclose()


async def test_undeclared_business_tool_in_native_memory_call_cannot_be_accepted(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([skill_reply("Bash", {"command": "echo forbidden"}, "unknown")]) as (
        url,
        captured,
    ):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "job",
            "memory_extract",
            retry_policy=RetryPolicy(max_retries=0),
        )
        try:
            store, _, _ = make_store(tmp_path)
            view = await store.open_update()
            manager = create_memory_store_manager(
                facade(owner, "memory_manager"),
                schemas=[SemanticMemory],
                namespace=USER,
                store=view,
                query_limit=4,
            )
            with pytest.raises(ModelError) as error:
                await manager.ainvoke(
                    {
                        "messages": [HumanMessage(content="Remember lasting preference")],
                        "max_steps": 1,
                    }
                )
            assert error.value.code == "component_tool_not_declared"
            assert len(captured) == 1 and not store.read(USER)["processed_batches"]
            assert not await view.asearch(USER)
            record = (await owner.records.list_requests("s"))[0]
            assert record.usage.total_tokens == 25
        finally:
            await owner.aclose()
