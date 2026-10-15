"""Official proactive tools publish through trusted task evidence and one Store."""

import json
from dataclasses import replace

import pytest
from tests.services.test_json_memory import service_at
from tests.services.test_memory_evidence import proposal
from tests.support.execution import provider
from tests.support.http_provider import protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.core.errors import ToolError
from pycode.core.session import EntryDraft
from pycode.core.tools import ToolContext
from pycode.services.permission import MemoryToolGate, ReadOnlyToolGate
from pycode.workflows.memory_tools import memory_tools


async def current_context(history, tmp_path):
    await history.append_entry(
        EntryDraft("custom", run_id="r", data={"kind": "run_start", "input_id": "u"})
    )
    return ToolContext("s", "r", "op", str(tmp_path), "call")


@pytest.mark.parametrize("supported", [True, False])
async def test_official_manage_reports_success_only_after_actual_commit(
    tmp_path, monkeypatch, supported
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    response = skill_reply(
        "MemoryEvidenceVerdict",
        {
            "supported": supported,
            "durable": True,
            "scope_valid": True,
            "reason": "current user preference",
        },
        "review",
    )
    with protocol_server([response]) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        try:
            context = await current_context(history, tmp_path)
            tool = next(t for t in memory_tools(service) if t.spec.name == "manage_user_memory")
            data = proposal(entry)
            data["content"]["sources"][0].update(session_id="current", entry_id="current")
            args = {"action": "create", "content": data}
            if supported:
                result = await tool.execute(context, args)
                assert not result.is_error
                ids = result.details["memory_ids"]
                assert len(ids) == 1
                stored = await service.store.aget(service.user_namespace, ids[0])
                assert stored.value["content"]["body"] == "Always use pytest."
            else:
                with pytest.raises(ToolError) as error:
                    await tool.execute(context, args)
                assert error.value.code == "memory_claim_unsupported"
                assert not service.store.read(service.user_namespace)["records"]
            assert not service.store.read(service.user_namespace)["processed_batches"]
            assert len(captured) == 1
        finally:
            await service.aclose()
            await history.aclose()


async def test_search_and_manage_share_scope_and_no_duplicate_active_create(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {}

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"]["tools"]}
        if "MemoryDirectoryChoices" in names:
            service = holder["service"]
            keys = service.store.read(service.user_namespace)["records"]
            return skill_reply(
                "MemoryDirectoryChoices",
                {
                    "selections": [
                        {"id": json.dumps([service.user_namespace, key]), "score": 0.95}
                        for key in keys
                    ]
                },
                "directory",
            )
        return skill_reply(
            "MemoryEvidenceVerdict",
            {"supported": True, "durable": True, "scope_valid": True, "reason": "supported"},
            "review",
        )

    with protocol_server(respond) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        holder["service"] = service
        try:
            context = await current_context(history, tmp_path)
            tools = {tool.spec.name: tool for tool in memory_tools(service)}
            data = proposal(entry)
            data["content"]["sources"][0].update(session_id="current", entry_id="current")
            args = {"action": "create", "content": data}
            first = await tools["manage_user_memory"].execute(context, args)
            second = await tools["manage_user_memory"].execute(
                replace(context, operation_id="second", call_id="second"), args
            )
            assert first.details["memory_ids"] == second.details["memory_ids"]
            searched = await tools["search_user_memory"].execute(
                replace(context, operation_id="search", call_id="search"), {"query": "pytest"}
            )
            records = searched.details["memory_records"]
            assert len(records) == 1 and records[0]["namespace"] == list(service.user_namespace)
            assert records[0]["memory_id"] == first.details["memory_ids"][0]
            assert len(records[0]["version"]) == 64
            assert not service.store.read(
                next(ns for ns, scope in service.scopes.items() if scope == "project")
            )["records"]
        finally:
            await service.aclose()
            await history.aclose()


async def test_memory_grant_does_not_bypass_worker_off_or_unregistered_write(tmp_path):
    from pycode.core.messages import ToolCall
    from pycode.core.tools import ToolSpec

    history, service, _ = await service_at(tmp_path)
    try:
        context = await current_context(history, tmp_path)
        spec = ToolSpec("manage_user_memory", "memory", {}, category="write", source="memory")
        gate = MemoryToolGate(ReadOnlyToolGate(), (spec,))
        await gate.authorize(context, spec, ToolCall("c", spec.name, {}))
        with pytest.raises(ToolError):
            await gate.authorize(
                context,
                replace(spec, input_schema={"different": True}),
                ToolCall("c", spec.name, {}),
            )
        with pytest.raises(ToolError):
            await service.active_operation(
                replace(context, agent_id="worker"),
                service.user_namespace,
                {},
                manage=True,
                name=spec.name,
            )
        assert not memory_tools(service)
    finally:
        await service.aclose()
        await history.aclose()


async def test_main_graph_registers_official_manage_and_settles_without_memory_approval(
    tmp_path, monkeypatch
):
    from tests.support.http_provider import chat_completion

    from pycode.app.config import AppConfig
    from pycode.app.container import build_session_repository, open_agent_session
    from pycode.core.messages import Message

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    primary_calls = []
    args = {"action": "create", "content": proposal("current")}
    args["content"]["content"]["sources"][0].update(session_id="current", entry_id="current")

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        if "MemoryEvidenceVerdict" in names:
            return skill_reply(
                "MemoryEvidenceVerdict",
                {
                    "supported": True,
                    "durable": True,
                    "scope_valid": True,
                    "reason": "lasting preference",
                },
                "review",
            )
        if "MemoryDirectoryChoices" in names:
            text = request["body"]["messages"][-1]["content"]
            directory = json.loads(text.split("Memory directory:\n", 1)[1])
            return skill_reply(
                "MemoryDirectoryChoices",
                {"selections": [{"id": row["id"], "score": 1.0} for row in directory]},
                "directory",
            )
        if "SemanticMemory" in names:
            return chat_completion()
        primary_calls.append(request)
        assert {
            "manage_user_memory",
            "search_user_memory",
            "manage_project_memory",
            "search_project_memory",
        } <= names
        if len(primary_calls) == 1:
            return skill_reply("manage_user_memory", args, "active-create")
        return chat_completion()

    with protocol_server(respond) as (url, captured):
        selected = provider(url)
        selected = replace(selected, tool_selector=replace(selected.tool_selector, enabled=False))
        config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
        session = await open_agent_session(
            config,
            (selected,),
            "s",
            create=True,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        try:
            result = await session.run(Message("u", "user", "Always use pytest."))
            assert result.outcome == "completed" and len(primary_calls) == 2
            records = (await session.memory_list())["records"]
            assert len(records) == 1 and records[0]["content"] == "Always use pytest."
            await session.collect_memory()
            assert len((await session.memory_list())["records"]) == 1
            reader = await build_session_repository(config).open("s")
            try:
                snapshot = await reader.snapshot()
                results = [
                    e.message.tool_result
                    for e in snapshot.entries
                    if e.message is not None
                    and e.message.tool_result is not None
                    and e.message.tool_result.name == "manage_user_memory"
                ]
                assert len(results) == 1 and not results[0].is_error
                assert results[0].call_id == "active-create" and results[0].output_id
                assert tuple(results[0].details["memory_ids"]) == (records[0]["memory_id"],)
            finally:
                await reader.aclose()
        finally:
            await session.aclose()
