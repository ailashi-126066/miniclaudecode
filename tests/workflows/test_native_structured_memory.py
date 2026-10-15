"""Native structured output preserves provider protocol and metered raw responses."""

import json
from dataclasses import replace

import pytest
from tests.support.execution import provider
from tests.support.http_provider import (
    anthropic_message,
    chat_completion,
    protocol_server,
    responses_completion,
)
from tests.workflows.test_memory_store_http import facade
from tests.workflows.test_skills_http import skill_reply
from typing_extensions import TypedDict

from pycode.app.config import AppConfig
from pycode.app.container import build_background_model
from pycode.core.requests import RetryPolicy


@pytest.mark.parametrize("include_raw", [False, True])
async def test_typed_dict_rejects_wrong_field_type_after_native_parse_and_one_metered_call(
    tmp_path, monkeypatch, include_raw
):
    from pydantic import ValidationError

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([structured_reply("openai", {"rule": {"wrong": "type"}})]) as (
        url,
        captured,
    ):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "invalid-typed",
            "memory_extract",
            retry_policy=RetryPolicy(max_retries=0),
        )
        try:
            chain = facade(owner, "typed").with_structured_output(
                RuleOutput, method="json_schema", include_raw=include_raw
            )
            if include_raw:
                result = await chain.ainvoke("Create a rule.")
                assert result["parsed"] is None and isinstance(
                    result["parsing_error"], ValidationError
                )
                assert result["raw"] is not None
            else:
                with pytest.raises(ValidationError):
                    await chain.ainvoke("Create a rule.")
            assert len(captured) == len(await owner.records.list_requests("s")) == 1
        finally:
            await owner.aclose()


class RuleOutput(TypedDict):
    rule: str


def structured_reply(kind, value):
    if kind == "deepseek":
        return skill_reply("RuleOutput", value, "native-choice")
    if kind == "anthropic":
        response = anthropic_message()
    elif kind == "responses":
        response = responses_completion()
    else:
        response = chat_completion()
    response.body = (
        response.body.decode()
        .replace('"done"', json.dumps(json.dumps(value, ensure_ascii=False)))
        .encode()
    )
    return response


@pytest.mark.parametrize("kind", ["openai", "responses", "deepseek", "anthropic"])
async def test_explicit_json_schema_preserves_provider_binding_and_accounting(
    tmp_path, monkeypatch, kind
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([structured_reply(kind, {"rule": "说明参数来源。"})]) as (url, captured):
        configured = replace(
            provider(url),
            implementation="openai" if kind == "responses" else kind,
            openai_api="responses"
            if kind == "responses"
            else ("chat_completions" if kind == "openai" else None),
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
            chain = facade(owner, "native_output").with_structured_output(
                RuleOutput, method="json_schema"
            )
            result = await chain.ainvoke("Create the scoped behavior rule.")
            body = captured[0]["body"]
            if kind == "openai":
                assert body["response_format"]["type"] == "json_schema"
            elif kind == "responses":
                assert body["text"]["format"]["type"] == "json_schema"
            elif kind == "anthropic":
                assert body["output_config"]["format"]["type"] == "json_schema"
            else:
                assert body["tools"][0]["function"]["name"] == "RuleOutput"
            assert result == {"rule": "说明参数来源。"}
            records = await owner.records.list_requests("s")
            assert len(captured) == len(records) == 1 and records[0].logical_id == "native_output:0"
            assert records[0].usage.input_tokens == 20 and records[0].usage.output_tokens == 5
        finally:
            await owner.aclose()


@pytest.mark.parametrize("kind", ["openai", "responses", "deepseek", "anthropic"])
async def test_explicit_schema_include_raw_preserves_native_parser_and_one_bill(
    tmp_path, monkeypatch, kind
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([structured_reply(kind, {"rule": "a scoped rule"})]) as (url, captured):
        configured = replace(
            provider(url),
            implementation="openai" if kind == "responses" else kind,
            openai_api="responses"
            if kind == "responses"
            else ("chat_completions" if kind == "openai" else None),
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
            result = (
                await facade(owner, "native")
                .with_structured_output(RuleOutput, method="json_schema", include_raw=True)
                .ainvoke("Produce a rule.")
            )
            assert result["parsed"] == {"rule": "a scoped rule"} and result["parsing_error"] is None
            assert result["raw"].response_metadata["pycode_request_id"]
            assert len(captured) == len(await owner.records.list_requests("s")) == 1
        finally:
            await owner.aclose()


async def test_native_response_schema_counts_toward_input_budget_before_sdk_request(
    tmp_path, monkeypatch
):
    from pycode.core.errors import ModelError

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([]) as (url, captured):
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
            schema = {
                "title": "LargeRule",
                "type": "object",
                "properties": {"rule": {"type": "string", "description": "d" * 10000}},
                "required": ["rule"],
            }
            model = facade(owner, "native").model_copy(update={"input_limit": 1000})
            with pytest.raises(ModelError) as error:
                await model.with_structured_output(schema, method="json_schema").ainvoke("rule")
            assert error.value.code == "component_input_overflow"
            assert not captured and not await owner.records.list_requests("s")
        finally:
            await owner.aclose()


@pytest.mark.parametrize("kind", ["openai", "responses", "anthropic"])
async def test_partial_native_json_never_reaches_parser_or_success_result(
    tmp_path, monkeypatch, kind
):
    from pycode.core.errors import ModelError

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    response = structured_reply(kind, {"rule": "partial"})
    if kind == "anthropic":
        response.body = response.body.replace(
            b'"stop_reason": "end_turn"', b'"stop_reason": "max_tokens"'
        )
    elif kind == "responses":
        response.body = response.body.replace(
            b'"status": "completed"', b'"status": "incomplete"'
        ).replace(b"response.completed", b"response.incomplete")
    else:
        response.body = response.body.replace(
            b'"finish_reason": "stop"', b'"finish_reason": "length"'
        )
    with protocol_server([response]) as (url, captured):
        configured = replace(
            provider(url),
            implementation="openai" if kind == "responses" else kind,
            openai_api="responses"
            if kind == "responses"
            else ("chat_completions" if kind == "openai" else None),
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
            with pytest.raises(ModelError):
                await (
                    facade(owner, "native")
                    .with_structured_output(RuleOutput, method="json_schema", include_raw=True)
                    .ainvoke("rule")
                )
            records = await owner.records.list_requests("s")
            assert len(captured) == len(records) == 1 and records[0].usage.input_tokens == 20
        finally:
            await owner.aclose()
