"""Native fallback keeps actual candidate identity, request accounting and one durable reply."""

import asyncio
import json
import threading
from dataclasses import replace

import pytest

from pycode.app.bootstrap import compose_application
from pycode.app.config import AppConfig
from pycode.context.adapters.summary import SUMMARY_SYSTEM
from pycode.kernel.domain.messages import Message
from pycode.models.domain.models import ModelReference
from tests.agent.adapters.test_agent import Recorder
from tests.context.adapters.test_summary_http import seed
from tests.models.adapters.test_tool_selection import (
    Probe,
    reply_selection,
    selection_request,
    selection_settings,
)
from tests.skills.adapters.test_skills_http import skill_reply
from tests.support.execution import provider
from tests.support.http_provider import HTTPResponse, chat_completion, protocol_server


def failure(status=503):
    return HTTPResponse(
        json.dumps({"error": {"message": "synthetic failure"}}).encode(),
        status=status,
        content_type="application/json",
    )


def roster(url, *, skip=False, selector=False):
    references = (ModelReference("secondary", "fallback-model"),)
    extra = ()
    if skip:
        references = (ModelReference("incompatible", "no-tools"), *references)
        extra = (
            replace(provider(url), name="incompatible", model_id="no-tools", supports_tools=False),
        )
    primary = replace(provider(url), fallback_models=references)
    if selector:
        primary = replace(primary, tool_selector=selection_settings())
    secondary = replace(
        provider(url), name="secondary", model_id="fallback-model", context_window=64000
    )
    return primary, *extra, secondary


@pytest.mark.parametrize("skip", [False, True])
async def test_retry_exhaustion_then_native_fallback_accounts_actual_model_and_keeps_preference(
    tmp_path, monkeypatch, skip
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    primary_calls, secondary_calls = [], []
    preferred_models = []
    succeed = False

    def respond(request):
        preferred_models.append(request["body"]["model"])
        if request["body"]["model"] == "wire-model":
            primary_calls.append(request)
            return chat_completion() if succeed else failure()
        secondary_calls.append(request)
        return chat_completion()

    with protocol_server(respond) as (url, captured):
        configs = roster(url, skip=skip)
        session = await compose_application(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), configs
        ).open_session("s1", create=True, refresh_models=False)
        try:
            result = await asyncio.wait_for(session.run(Message("u", "user", "inspect")), 15)
            assert (
                result.outcome == "completed"
                and len(primary_calls) == 4
                and len(secondary_calls) == 1
            )
            assert len(captured) == 5 and "no-tools" not in preferred_models
            records = await session.request_records()
            assert [record.provider for record in records] == ["test"] * 4 + ["secondary"]
            assert [record.candidate_index for record in records] == [0] * 4 + [2 if skip else 1]
            assert [record.attempt for record in records] == [0, 1, 2, 3, 0]
            assert len({record.logical_id for record in records}) == 1
            assert records[-1].purpose == "agent" and records[-1].usage.total_tokens == 25
            state = await session.inspect()
            assistants = [
                entry["message"]
                for entry in state["snapshot"]["entries"]
                if entry["message"] and entry["message"]["role"] == "assistant"
            ]
            assert (
                len(assistants) == 1 and assistants[0]["model_selection"]["provider"] == "secondary"
            )
            changes = [
                entry for entry in state["snapshot"]["entries"] if entry["type"] == "model_change"
            ]
            assert len(changes) == 1 and changes[0]["selection"]["provider"] == "test"
            succeed = True
            await session.run(Message("next", "user", "next task"))
            assert preferred_models[-1] == "wire-model"
        finally:
            await session.aclose()


@pytest.mark.parametrize("status", [400, 401, 403])
async def test_non_switchable_provider_error_sends_no_fallback_request(
    tmp_path, monkeypatch, status
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([failure(status)]) as (url, captured):
        session = await compose_application(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), roster(url)
        ).open_session("s1", create=True, refresh_models=False)
        try:
            assert (await session.run(Message("u", "user", "inspect"))).outcome == "error"
            assert len(captured) == 1 and captured[0]["body"]["model"] == "wire-model"
            assert len(await session.request_records()) == 1
        finally:
            await session.aclose()


async def test_selection_is_reused_by_fallback_and_actual_schema_order_is_preserved(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    selected, primary, secondary = [], [], []

    def respond(request):
        if selection_request(request):
            selected.append(request)
            return reply_selection(request, ["probe_0"])
        if request["body"]["model"] == "wire-model":
            primary.append(request)
            return failure()
        secondary.append(request)
        return chat_completion()

    with protocol_server(respond) as (url, _):
        session = await compose_application(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), roster(url, selector=True)
        ).open_session(
            "s1", create=True, refresh_models=False, tools=(Probe("probe_0"), Probe("probe_1"))
        )
        try:
            assert (
                await asyncio.wait_for(session.run(Message("u", "user", "inspect")), 15)
            ).outcome == "completed"
            assert len(selected) == 1 and len(primary) == 4 and len(secondary) == 1
            assert primary[0]["body"]["tools"] == secondary[0]["body"]["tools"]
            assert (await session.usage())["by_origin_purpose"]["tool_selection"][
                "request_count"
            ] == 1
        finally:
            await session.aclose()


async def test_cancelled_primary_attempt_does_not_start_secondary(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    response = chat_completion()
    started, release = threading.Event(), threading.Event()
    response.started, response.release = started, release
    with protocol_server([response]) as (url, captured):
        session = await compose_application(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), roster(url)
        ).open_session("s1", create=True, refresh_models=False)
        running = asyncio.create_task(session.run(Message("u", "user", "inspect")))
        try:
            assert await asyncio.to_thread(started.wait, 5)
            running.cancel()
            with pytest.raises(asyncio.CancelledError):
                await running
            assert len(captured) == 1
            assert (await session.request_records())[0].status == "interrupted"
        finally:
            release.set()
            if not running.done():
                running.cancel()
                await asyncio.gather(running, return_exceptions=True)
            await session.aclose()


async def test_completed_tool_is_not_repeated_when_a_later_model_request_switches(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    probe = Probe("probe_0")
    primary = []

    def respond(request):
        if request["body"]["model"] == "wire-model":
            primary.append(request)
            return skill_reply("probe_0", {}, "completed-tool") if len(primary) == 1 else failure()
        return chat_completion()

    with protocol_server(respond) as (url, captured):
        session = await compose_application(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), roster(url)
        ).open_session("s1", create=True, tools=(probe,), refresh_models=False)
        try:
            assert (
                await asyncio.wait_for(session.run(Message("u", "user", "inspect")), 15)
            ).outcome == "completed"
            assert probe.calls == 1 and len(captured) == 6
            outputs = [
                entry["message"]["tool_result"]
                for entry in (await session.inspect())["snapshot"]["entries"]
                if entry["message"] and entry["message"]["tool_result"]
            ]
            assert len(outputs) == 1 and outputs[0]["call_id"] == "completed-tool"
        finally:
            await session.aclose()


async def test_failed_stream_partials_keep_their_request_identity_without_becoming_history(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    recorder = Recorder()

    def respond(request):
        response = chat_completion()
        if request["body"]["model"] == "wire-model":
            response.body = response.body.replace(
                b'"content": "done"', b'"content": "failed partial"'
            ).replace(b'"finish_reason": "stop"', b'"finish_reason": "network_error"')
        return response

    with protocol_server(respond) as (url, captured):
        session = await compose_application(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), roster(url)
        ).open_session("s1", create=True, consumers=(recorder,), refresh_models=False)
        try:
            assert (
                await asyncio.wait_for(session.run(Message("u", "user", "inspect")), 15)
            ).outcome == "completed"
            assert len(captured) == 5
            starts = [
                event.data
                for event in recorder.events
                if event.kind == "message_start" and event.data.get("role") == "assistant"
            ]
            assert [event["model_selection"]["provider"] for event in starts] == ["test"] * 4 + [
                "secondary"
            ]
            assert len({event["request_id"] for event in starts}) == 5
            deltas = [event.data for event in recorder.events if event.kind == "message_delta"]
            assert all(event["request_id"] for event in deltas)
            assert "failed partial" not in str((await session.inspect())["snapshot"]["entries"])
        finally:
            await session.aclose()


async def test_smaller_fallback_window_remeasures_and_safely_compacts_before_sdk_send(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(
        tmp_path, tmp_path / "data", memory_mode="off", summary_keep_recent_tokens=1000
    )
    await seed(config, characters=4000)
    primary, fallback, summaries = [], [], []

    def respond(request):
        if request["body"]["model"] == "wire-model":
            primary.append(request)
            return failure()
        if request["body"]["messages"][0]["content"] == SUMMARY_SYSTEM:
            summaries.append(request)
        else:
            fallback.append(request)
        return chat_completion()

    with protocol_server(respond) as (url, captured):
        configs = roster(url)
        configs = (
            replace(configs[0], context_window=64000),
            replace(configs[1], context_window=12000, max_output_tokens=1000),
        )
        session = await compose_application(config, configs).open_session(
            "s1", refresh_models=False
        )
        try:
            assert (
                await asyncio.wait_for(
                    session.run(Message("u", "user", "retain this new instruction")), 15
                )
            ).outcome == "completed"
            assert len(primary) == 4 and len(summaries) == 1 and len(fallback) == 1
            last = str(fallback[0]["body"]["messages"])
            assert "retain this new instruction" in last and "old task 0" not in last
            state = await session.inspect()
            assert state["context_usage"]["window"] == 12000
            assert any(entry["type"] == "compaction" for entry in state["snapshot"]["entries"])
            assert len(captured) == 6
        finally:
            await session.aclose()
