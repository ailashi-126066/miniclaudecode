"""Official selection runs through the SDK, permissions, accounting and final tool boundary."""

import asyncio
import json
import threading
from dataclasses import replace

import pytest

from pycode.app.composition import open_agent_session
from pycode.app.config import AppConfig
from pycode.kernel.contracts.messages import Message, ToolResult
from pycode.kernel.contracts.tools import ToolSpec
from pycode.models.adapters.tool_selection import REQUIRED_TOOLS
from pycode.models.domain.models import ModelReference, ToolSelectorSettings
from tests.agent.adapters.test_agent import Recorder
from tests.mcp.adapters.test_mcp import mcp_config
from tests.skills.adapters.test_skills_http import skill_reply
from tests.skills.services.test_skills import write_skill
from tests.support.execution import provider
from tests.support.http_provider import (
    HTTPResponse,
    anthropic_message,
    chat_completion,
    protocol_server,
)


class Probe:
    def __init__(self, name):
        self.spec = ToolSpec(
            name,
            "Synthetic read tool",
            {"type": "object", "properties": {}},
            category="read",
            execution_mode="parallel",
        )
        self.calls = 0

    async def execute(self, context, arguments):
        self.calls += 1
        return ToolResult(context.call_id, self.spec.name, "probe result")


def selection_settings(**values):
    return ToolSelectorSettings(
        max_tools=1, candidate_threshold=1, schema_token_threshold=1, **values
    )


def selection_request(request):
    tools = request["body"].get("tools", [])
    return bool(tools and "tools" in tools[0]["function"]["parameters"].get("properties", {}))


def reply_selection(request, choice):
    return skill_reply(
        request["body"]["tools"][0]["function"]["name"], {"tools": choice}, "selection"
    )


@pytest.mark.parametrize("case", ["valid", "empty", "duplicate", "unknown", "malformed", "length"])
async def test_sdk_selection_permissions_snapshot_metering_and_error_fallback(
    tmp_path, monkeypatch, case
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    choices, main = [], []
    probes = tuple(Probe(f"probe_{number}") for number in range(4))
    recorder = Recorder()

    def respond(request):
        if selection_request(request):
            choices.append(request)
            if case == "malformed":
                return chat_completion()
            names = [] if case == "empty" else ["probe_0"]
            if case == "duplicate":
                names.append("probe_0")
            if case == "unknown":
                names = ["not_declared"]
            response = reply_selection(request, names)
            if case == "length":
                response.body = response.body.replace(
                    b'"finish_reason": "tool_calls"', b'"finish_reason": "length"'
                )
            return response
        main.append(request)
        return chat_completion()

    with protocol_server(respond) as (url, requests):
        configured = replace(provider(url), tool_selector=selection_settings())
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (configured,),
            "s1",
            create=True,
            tools=probes,
            consumers=(recorder,),
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "completed"
            assert len(choices) == len(main) == 1 and len(requests) == 2
            names = {tool["function"]["name"] for tool in main[0]["body"]["tools"]}
            assert {"read_file", "ReadToolResult", "AskUserQuestion"} <= names
            if case in {"valid", "empty"}:
                assert names - REQUIRED_TOOLS == ({"probe_0"} if case == "valid" else set())
            else:
                assert {probe.spec.name for probe in probes} <= names
            state = await session.inspect()
            assert {tool["name"] for tool in state["context"]["checkpoint"]["model_tools"]} == names
            audit = [
                entry["data"]
                for entry in state["snapshot"]["entries"]
                if entry["data"].get("kind") == "tool_selection"
            ]
            assert len(audit) == 1
            if case in {"valid", "empty"}:
                assert audit[0]["schema_estimate_after"] < audit[0]["schema_estimate_before"]
            else:
                assert audit[0]["schema_estimate_after"] == audit[0]["schema_estimate_before"]
            assert audit[0]["status"] == ("selected" if case in {"valid", "empty"} else "fallback")
            usage = await session.usage()
            assert usage["by_purpose"]["tool_selection"]["request_count"] == 1
            assert usage["by_purpose"]["agent"]["request_count"] == 1
            assert usage["session"]["tokens"]["total_tokens"] == 50
            assert not any(
                event.kind == "message_delta" and event.data["text"] == "Checking"
                for event in recorder.events
            )
            assert all(probe.calls == 0 for probe in probes)
        finally:
            await session.aclose()


async def test_main_retry_reuses_selection_and_exact_tools_snapshot(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    choices, main = [], []

    def respond(request):
        if selection_request(request):
            choices.append(request)
            return reply_selection(request, ["probe_0"])
        main.append(request)
        if len(main) == 1:
            return HTTPResponse(
                b'{"error":{"message":"unavailable"}}', status=503, content_type="application/json"
            )
        return chat_completion()

    with protocol_server(respond) as (url, requests):
        configured = replace(provider(url), tool_selector=selection_settings())
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (configured,),
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "completed"
            assert len(choices) == 1 and len(main) == 2 and len(requests) == 3
            assert main[0]["body"]["tools"] == main[1]["body"]["tools"]
            usage = await session.usage()
            assert usage["by_purpose"]["tool_selection"]["request_count"] == 1
            assert usage["by_purpose"]["retry"]["request_count"] == 1
        finally:
            await session.aclose()


async def test_model_cannot_execute_an_unselected_registered_tool(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    probes = (Probe("probe_0"), Probe("probe_1"))
    main = []

    def respond(request):
        if selection_request(request):
            return reply_selection(request, ["probe_0"])
        main.append(request)
        return skill_reply("probe_1", {}, "forbidden") if len(main) == 1 else chat_completion()

    with protocol_server(respond) as (url, _):
        configured = replace(provider(url), tool_selector=selection_settings())
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (configured,),
            "s1",
            create=True,
            tools=probes,
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "completed"
            assert probes[1].calls == 0
            outputs = [
                entry["message"]["tool_result"]
                for entry in (await session.inspect())["snapshot"]["entries"]
                if entry["message"] and entry["message"]["tool_result"]
            ]
            assert len(outputs) == 1 and outputs[0]["error_code"] == "tool_not_declared"
            assert outputs[0]["call_id"] == "forbidden" and not outputs[0]["details"]["executed"]
        finally:
            await session.aclose()


async def test_cross_provider_selector_identity_and_primary_preference_remain_distinct(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    models = []

    def respond(request):
        models.append(request["body"]["model"])
        return (
            reply_selection(request, ["probe_0"])
            if selection_request(request)
            else chat_completion()
        )

    with protocol_server(respond) as (url, _):
        primary = replace(
            provider(url),
            tool_selector=selection_settings(model=ModelReference("selector", "selector-model")),
        )
        secondary = replace(provider(url), name="selector", model_id="selector-model")
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (primary, secondary),
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "completed"
            assert models == ["selector-model", "wire-model"]
            selection = (await session.inspect())["context"]["selection"]
            assert selection["provider"] == "test" and selection["model_id"] == "wire-model"
            usage = await session.usage()
            records = await session.request_records()
            selected_record = next(
                record for record in records if record.purpose == "tool_selection"
            )
            assert (
                selected_record.provider == "selector"
                and selected_record.model_id == "selector-model"
            )
            assert selected_record.usage.total_tokens == 25
            assert usage["by_origin_purpose"]["tool_selection"]["request_count"] == 1
        finally:
            await session.aclose()


async def test_plan_restrictions_precede_always_include_and_selector_candidates(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    captured = []

    def respond(request):
        captured.append(request)
        return (
            reply_selection(request, ["probe_0"])
            if selection_request(request)
            else chat_completion()
        )

    with protocol_server(respond) as (url, _):
        configured = replace(
            provider(url), tool_selector=selection_settings(always_include=("Bash",))
        )
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (configured,),
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        try:
            await session.enter_plan()
            assert (
                await session.run(Message("u", "user", "inspect in plan"))
            ).outcome == "completed"
            assert "Bash" not in str(captured[0]["body"])
            assert "Bash" not in {
                tool["function"]["name"] for tool in captured[-1]["body"]["tools"]
            }
        finally:
            await session.aclose()


async def test_cancelled_selection_sends_no_primary_request_and_settles_internal_attempt(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    started, release = threading.Event(), threading.Event()
    captured = []

    def respond(request):
        captured.append(request)
        response = reply_selection(request, ["probe_0"])
        response.started, response.release = started, release
        return response

    with protocol_server(respond) as (url, _):
        configured = replace(provider(url), tool_selector=selection_settings())
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (configured,),
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        running = asyncio.create_task(session.run(Message("u", "user", "inspect")))
        try:
            assert await asyncio.to_thread(started.wait, 5)
            running.cancel()
            with pytest.raises(asyncio.CancelledError):
                await running
            assert len(captured) == 1 and selection_request(captured[0])
            selected_record = next(
                record
                for record in await session.request_records()
                if record.purpose == "tool_selection"
            )
            assert selected_record.status == "interrupted"
            assert selected_record.error_code == "model_interrupted"
            assert selected_record.usage.total_tokens is None
        finally:
            release.set()
            if not running.done():
                running.cancel()
                await asyncio.gather(running, return_exceptions=True)
            await session.aclose()


async def test_native_mcp_candidate_is_selected_without_execution_or_registry_readdition(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await mcp_config(config)
    captured = []

    def respond(request):
        captured.append(request)
        return (
            reply_selection(request, ["mcp__srv__echo"])
            if selection_request(request)
            else chat_completion()
        )

    with protocol_server(respond) as (url, _):
        configured = replace(provider(url), tool_selector=selection_settings())
        session = await open_agent_session(
            config, (configured,), "s1", create=True, refresh_models=False
        )
        try:
            assert (await session.run(Message("u", "user", "inspect MCP"))).outcome == "completed"
            assert "mcp__srv__echo" in str(captured[0]["body"])
            declared = {tool["function"]["name"] for tool in captured[-1]["body"]["tools"]}
            assert "mcp__srv__echo" in declared
            assert "mcp__srv__mutate" not in declared
            assert not (tmp_path / "effects.txt").exists()
        finally:
            await session.aclose()


async def test_skill_allowlist_precedes_selection_and_no_hidden_body_is_loaded(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await write_skill(
        config.user_data / "skills",
        body="PRIVATE SKILL BODY",
        allowed="Read",
    )
    selected, main = [], []

    def respond(request):
        if selection_request(request):
            selected.append(request)
            return reply_selection(request, ["probe_0"])
        main.append(request)
        return (
            skill_reply("read_file", {"file_path": "/skills/user/example/SKILL.md"}, "load")
            if len(main) == 1
            else chat_completion()
        )

    with protocol_server(respond) as (url, _):
        configured = replace(
            provider(url), tool_selector=selection_settings(always_include=("Bash",))
        )
        session = await open_agent_session(
            config,
            (configured,),
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "use example"))).outcome == "completed"
            assert len(selected) == 1 and "PRIVATE SKILL BODY" not in str(selected[0]["body"])
            assert "PRIVATE SKILL BODY" in str(main[-1]["body"]["messages"])
            names = {tool["function"]["name"] for tool in main[-1]["body"]["tools"]}
            assert names == {"read_file", "SkillSearch"}
        finally:
            await session.aclose()


@pytest.mark.parametrize("limit", ["input", "incompatible", "strict"])
async def test_selection_budget_capability_and_strict_failure_do_not_send_invalid_requests(
    tmp_path, monkeypatch, limit
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    captured = []

    def respond(request):
        captured.append(request)
        return chat_completion()

    with protocol_server(respond) as (url, _):
        options = (
            {"input_limit": 1}
            if limit == "input"
            else {"on_failure": "error"}
            if limit == "strict"
            else {
                "model": ModelReference("incompatible", "no-tools"),
            }
        )
        primary = replace(provider(url), tool_selector=selection_settings(**options))
        roster = (primary,)
        if limit == "incompatible":
            roster += (
                replace(
                    provider(url), name="incompatible", model_id="no-tools", supports_tools=False
                ),
            )
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            roster,
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        try:
            result = await session.run(Message("u", "user", "inspect"))
            assert len(captured) == 1
            assert selection_request(captured[0]) == (limit == "strict")
            assert result.outcome == ("error" if limit == "strict" else "completed")
            audit = [
                entry["data"]
                for entry in (await session.inspect())["snapshot"]["entries"]
                if entry["data"].get("kind") == "tool_selection"
            ]
            assert audit[0]["status"] == ("failed" if limit == "strict" else "fallback")
        finally:
            await session.aclose()


@pytest.mark.parametrize("kind", ["deepseek", "anthropic"])
async def test_selection_schema_protocol_uses_current_provider_sdk(tmp_path, monkeypatch, kind):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    captured = []

    def respond(request):
        captured.append(request)
        if kind == "deepseek":
            return (
                reply_selection(request, ["probe_0"])
                if selection_request(request)
                else chat_completion()
            )
        tools = request["body"].get("tools", [])
        selecting = bool(tools and "tools" in tools[0]["input_schema"].get("properties", {}))
        response = anthropic_message(tool=selecting)
        if selecting:
            response.body = (
                response.body.decode()
                .replace('"name": "echo"', '"name": ' + json.dumps(tools[0]["name"]))
                .replace(
                    json.dumps('{"value":"wire"}'), json.dumps(json.dumps({"tools": ["probe_0"]}))
                )
                .encode()
            )
        return response

    with protocol_server(respond) as (url, _):
        configured = replace(
            provider(url), implementation=kind, openai_api=None, tool_selector=selection_settings()
        )
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (configured,),
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "completed"
            assert len(captured) == 2
            last = captured[-1]["body"]["tools"]
            names = (
                {tool["name"] for tool in last}
                if kind == "anthropic"
                else {tool["function"]["name"] for tool in last}
            )
            assert "probe_0" in names and "probe_1" not in names
            selected_record = next(
                record
                for record in await session.request_records()
                if record.purpose == "tool_selection"
            )
            assert selected_record.status == "completed"
            assert selected_record.usage.input_tokens == 20
            assert selected_record.usage.output_tokens == 5
            assert selected_record.usage.total_tokens == (None if kind == "anthropic" else 25)
        finally:
            await session.aclose()


async def test_readonly_role_cannot_restore_disallowed_tools_through_always_include(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    captured = []

    def respond(request):
        captured.append(request)
        return (
            reply_selection(request, ["probe_0"])
            if selection_request(request)
            else chat_completion()
        )

    with protocol_server(respond) as (url, _):
        configured = replace(
            provider(url),
            tool_selector=selection_settings(always_include=("Bash", "write_file", "edit_file")),
        )
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off", read_only=True),
            (configured,),
            "s1",
            create=True,
            tools=(Probe("probe_0"), Probe("probe_1")),
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "completed"
            names = {tool["function"]["name"] for tool in captured[-1]["body"]["tools"]}
            assert not {"Bash", "write_file", "edit_file"}.intersection(names)
            assert "probe_0" in names
        finally:
            await session.aclose()


@pytest.mark.parametrize("enabled", [False, True])
async def test_small_or_disabled_selection_sends_only_the_primary_sdk_request(
    tmp_path, monkeypatch, enabled
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([chat_completion()]) as (url, captured):
        settings = ToolSelectorSettings(enabled=enabled, max_tools=100)
        configured = replace(provider(url), tool_selector=settings)
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (configured,),
            "s1",
            create=True,
            refresh_models=False,
        )
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "completed"
            assert len(captured) == 1 and not selection_request(captured[0])
            assert "tool_selection" not in (await session.usage())["by_purpose"]
        finally:
            await session.aclose()
