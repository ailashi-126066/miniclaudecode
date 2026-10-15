"""Native switching never hides critical failures or retries the entire model sequence."""

from dataclasses import replace

import pytest

from pycode.app.composition import build_runtime
from pycode.app.config import AppConfig
from pycode.kernel.contracts.messages import Message
from pycode.kernel.errors import StorageError
from pycode.models.domain.models import ModelReference
from pycode.models.domain.requests import RetryPolicy
from tests.agent.adapters.test_agent import Journal
from tests.models.adapters.test_model_fallback import failure
from tests.support.execution import provider
from tests.support.http_provider import (
    anthropic_message,
    chat_completion,
    protocol_server,
    responses_completion,
)


async def open_runtime(tmp_path, url, roster, consumer=()):
    by_name = {config.name: config for config in roster}

    def resolve(name, model):
        config = by_name[name]
        assert config.model_id == model
        return config

    return build_runtime(
        AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
        roster[0],
        session_id="s",
        run_id="r",
        journal=Journal(),
        tools=(),
        consumers=consumer,
        retry_policy=RetryPolicy(max_retries=0),
        model_resolver=resolve,
    )


def configs(url, count=2):
    secondary = replace(provider(url), name="second", model_id="second-model")
    third = replace(provider(url), name="third", model_id="third-model")
    refs = (ModelReference("second", "second-model"),)
    if count == 3:
        refs += (ModelReference("third", "third-model"),)
    primary = replace(provider(url), fallback_models=refs)
    return (primary, secondary, third) if count == 3 else (primary, secondary)


async def test_each_candidate_failed_once_then_all_failure_is_terminal(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([failure(), failure(), failure()]) as (url, captured):
        runtime = await open_runtime(tmp_path, url, configs(url, 3))
        try:
            result = await runtime.runtime.run((Message("u", "user", "inspect"),), runtime.context)
            assert result.outcome == "error"
            assert [request["body"]["model"] for request in captured] == [
                "wire-model",
                "second-model",
                "third-model",
            ]
            records = await runtime.request_store.list_requests("s")
            assert [record.candidate_index for record in records] == [0, 1, 2]
            assert all(record.attempt == 0 and record.status == "failed" for record in records)
        finally:
            await runtime.aclose()


async def test_secondary_auth_error_does_not_send_third_candidate(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([failure(), failure(401)]) as (url, captured):
        runtime = await open_runtime(tmp_path, url, configs(url, 3))
        try:
            result = await runtime.runtime.run((Message("u", "user", "inspect"),), runtime.context)
            assert result.outcome == "error" and len(captured) == 2
            assert captured[-1]["body"]["model"] == "second-model"
        finally:
            await runtime.aclose()


@pytest.mark.parametrize("kind", ["program", "persistence"])
async def test_critical_candidate_preparation_failure_is_propagated_without_third_request(
    tmp_path, monkeypatch, kind
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")

    with protocol_server([failure()]) as (url, captured):
        runtime = await open_runtime(tmp_path, url, configs(url, 3))
        original = runtime.context.model_fallback.configure

        def configure(context, selected):
            if selected.name == "second":
                if kind == "program":
                    raise ValueError("candidate defect")
                raise StorageError("critical_write_failed", "test")
            original(context, selected)

        runtime.context.model_fallback.configure = configure
        try:
            with pytest.raises(ValueError if kind == "program" else StorageError):
                await runtime.runtime.run((Message("u", "user", "inspect"),), runtime.context)
            assert len(captured) == 1
        finally:
            await runtime.aclose()


async def test_unused_fallback_with_missing_credentials_does_not_affect_primary_success(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    monkeypatch.delenv("PYCODE_MISSING_FALLBACK_KEY", raising=False)
    with protocol_server([chat_completion()]) as (url, captured):
        roster = configs(url)
        roster = (roster[0], replace(roster[1], api_key_env="PYCODE_MISSING_FALLBACK_KEY"))
        runtime = await open_runtime(tmp_path, url, roster)
        try:
            result = await runtime.runtime.run((Message("u", "user", "inspect"),), runtime.context)
            assert result.outcome == "completed" and len(captured) == 1
            assert runtime.context.model_fallback.owners == {}
        finally:
            await runtime.aclose()


@pytest.mark.parametrize("kind", ["deepseek", "anthropic", "responses"])
async def test_fallback_uses_its_own_provider_protocol_and_output_configuration(
    tmp_path, monkeypatch, kind
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    captured = []

    def respond(request):
        captured.append(request)
        if request["body"]["model"] == "wire-model":
            return failure()
        if kind == "anthropic":
            return anthropic_message()
        if kind == "responses":
            return responses_completion()
        return chat_completion()

    with protocol_server(respond) as (url, _):
        roster = configs(url)
        secondary = replace(roster[1], max_output_tokens=1000)
        if kind == "anthropic":
            secondary = replace(
                secondary, implementation="anthropic", openai_api=None, base_url=url
            )
        elif kind == "deepseek":
            secondary = replace(secondary, implementation="deepseek", openai_api=None)
        else:
            secondary = replace(secondary, openai_api="responses")
        runtime = await open_runtime(tmp_path, url, (roster[0], secondary))
        try:
            result = await runtime.runtime.run(
                (Message("u", "user", "retain my instruction"),), runtime.context
            )
            assert result.outcome == "completed" and len(captured) == 2
            assert runtime.context.selection.model_id == "second-model"
            record = (await runtime.request_store.list_requests("s"))[-1]
            assert record.model_id == "second-model" and record.usage.input_tokens == 20
            assert record.candidate_index == 1
            if kind == "responses":
                assert captured[-1]["path"] == "/v1/responses"
            elif kind == "anthropic":
                assert captured[-1]["path"] == "/v1/messages"
                assert captured[-1]["body"]["max_tokens"] == 1000
            else:
                assert captured[-1]["body"]["max_tokens"] == 1000
        finally:
            await runtime.aclose()
