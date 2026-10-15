"""Official prompt optimizers stage durable rules through the existing metered lifecycle."""

import json
from dataclasses import replace

import pytest
from tests.adapters.storage.test_json_memory import PROJECT, USER, Selector, value
from tests.services.test_memory_evidence import approve, service, setup
from tests.support.execution import provider
from tests.support.http_provider import anthropic_message, chat_completion, protocol_server
from tests.workflows.test_memory_store_http import facade
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_background_model
from pycode.core.errors import ModelError, ToolError
from pycode.core.memory_evidence import (
    MemoryBehaviorFeedback,
    MemoryEvidenceDecision,
    MemoryEvidencePolicy,
)
from pycode.core.requests import RetryPolicy
from pycode.core.summary_source import data_hash
from pycode.services.memory_commit import MemoryCommitService
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.workflows.memory_rules import MemoryRuleOptimizer


def rule(entry, body="Explain argument flow with a short example."):
    data = value(body, kind="ProceduralMemory")
    data["content"]["sources"] = [
        {"session_id": "s", "entry_id": entry, "quote": "Always use pytest."}
    ]
    return data


def feedback(entry, *, durable=True, explicit=True):
    return MemoryBehaviorFeedback(
        "Always use short examples and explain where arguments come from.",
        "Explanation style",
        "Use brief examples.",
        ("Code explanation",),
        ({"session_id": "s", "entry_id": entry, "quote": "Always use pytest."},),
        MemoryEvidenceDecision(True, durable, True),
        explicit,
    )


def json_reply(kind, new_prompt):
    output = {
        "logic": "fixture supported improvement",
        "update_prompt": True,
        "new_prompt": new_prompt,
    }
    if kind == "deepseek":
        return skill_reply("GeneralResponse", output, "prompt-result")
    result = anthropic_message() if kind == "anthropic" else chat_completion()
    result.body = result.body.decode().replace('"done"', json.dumps(json.dumps(output))).encode()
    return result


@pytest.mark.parametrize("count", [0, 1, 2])
async def test_official_no_change_flag_keeps_each_input_rule_and_new_draft(
    tmp_path, monkeypatch, count
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, permit, entry = await setup(tmp_path)
    store.selector = Selector()
    try:
        if count:
            view = await store.open_update()
            for index in range(count):
                await view.aput(
                    USER, f"rule-{index}", rule(entry, f"Keep {{{{variable}}}} in rule {index}.")
                )
            await (await service(history, permit, approve)).commit(view, USER)
        ids = ["rule-" + data_hash([USER, f"rule-{index}"]) for index in range(count)]

        def respond(request):
            names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
            if "Classify" in names:
                return skill_reply(
                    "Classify", {"reasoning": "both relevant", "which": ids}, "classify"
                )
            response = json_reply("openai", "")
            response.body = response.body.replace(b"true", b"false")
            return response

        with protocol_server(respond) as (url, captured):
            owner = build_background_model(
                AppConfig(tmp_path, tmp_path / "data"),
                provider(url),
                (),
                "s",
                "unchanged",
                "memory_extract",
                retry_policy=RetryPolicy(max_retries=0),
            )
            try:
                evidence = await capture_memory_evidence(await history.snapshot())
                policy = MemoryEvidencePolicy(evidence, {USER: "user", PROJECT: "project"})
                view = await store.open_update()
                keys = await MemoryRuleOptimizer(facade(owner, "unchanged"), policy).optimize(
                    view, USER, feedback(entry), batch_id="batch"
                )
                assert len(keys) == max(1, count)
                for key in keys:
                    expected = (
                        f"Keep {{{{variable}}}} in rule {key.rsplit('-', 1)[1]}."
                        if count
                        else feedback(entry).text
                    )
                    assert (await view.aget(USER, key)).value["content"]["body"] == expected
                assert len(captured) == (3 if count == 2 else 1)
            finally:
                await owner.aclose()
    finally:
        await history.aclose()


@pytest.mark.parametrize("kind", ["openai", "deepseek", "anthropic"])
@pytest.mark.parametrize("count", [0, 1, 2])
async def test_official_single_or_multi_prompt_branch_is_exclusive_and_keeps_ids(
    tmp_path, monkeypatch, kind, count
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, permit, e = await setup(tmp_path)
    store.selector = Selector()
    try:
        if count:
            view = await store.open_update()
            for i in range(count):
                await view.aput(USER, f"rule-{i}", rule(e, f"Original rule {i}."))
            await (await service(history, permit, approve)).commit(view, USER)
        ids = ["rule-" + data_hash([USER, f"rule-{i}"]) for i in range(count)]

        def respond(request):
            tools = request["body"].get("tools", [])
            classify = (
                tools
                and (tools[0].get("function", {}).get("name") or tools[0].get("name")) == "Classify"
            )
            if classify:
                if kind == "anthropic":
                    result = anthropic_message(tool=True)
                    result.body = (
                        result.body.decode()
                        .replace('"name": "echo"', '"name": "Classify"')
                        .replace(
                            json.dumps('{"value":"wire"}'),
                            json.dumps(
                                json.dumps({"reasoning": "relevant feedback", "which": ids})
                            ),
                        )
                        .encode()
                    )
                    return result
                return skill_reply(
                    "Classify", {"reasoning": "relevant feedback", "which": ids}, "classify"
                )
            return json_reply(
                kind, "Explain parameter inputs and return values with a short example."
            )

        with protocol_server(respond) as (url, captured):
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
                evidence = await capture_memory_evidence(await history.snapshot())
                policy = MemoryEvidencePolicy(evidence, {USER: "user", PROJECT: "project"})
                view = await store.open_update()
                keys = await MemoryRuleOptimizer(facade(owner, "memory_rule"), policy).optimize(
                    view, USER, feedback(e), batch_id="batch"
                )
                assert len(keys) == max(1, count)
                assert len(captured) == (3 if count == 2 else 1)
                for key in keys:
                    staged = await view.aget(USER, key)
                    assert (
                        staged.value["content"]["body"]
                        == "Explain parameter inputs and return values with a short example."
                    )
                    assert (await store.aget(USER, key) is not None) == (count > 0)
                if count:
                    assert set(keys) == {f"rule-{i}" for i in range(count)}
                    for key in keys:
                        assert (await view.aget(USER, key)).key == key
                report = await MemoryCommitService(history, policy, permit, approve).commit(
                    view, USER
                )
                assert report.can_advance
                records = await owner.records.list_requests("s")
                assert len(records) == len(captured) and all(
                    r.usage.input_tokens == 20 and r.usage.output_tokens == 5 for r in records
                )
                assert len({r.logical_id for r in records}) == len(records)
            finally:
                await owner.aclose()
    finally:
        await history.aclose()


@pytest.mark.parametrize("case", ["none", "temporary", "unsupported", "wrong_scope"])
async def test_no_lasting_behavior_feedback_skips_query_and_optimizer(tmp_path, monkeypatch, case):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, permit, e = await setup(tmp_path)
    store.selector = Selector()
    selected = []

    async def choose(*args):
        selected.append(args)
        return []

    store.selector = choose
    try:
        data = None if case == "none" else feedback(e)
        if case == "temporary":
            data = replace(data, decision=MemoryEvidenceDecision(True, False, True))
        elif case == "unsupported":
            data = replace(data, decision=MemoryEvidenceDecision(False, True, True))
        elif case == "wrong_scope":
            data = replace(data, decision=MemoryEvidenceDecision(True, True, False))
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
                policy = MemoryEvidencePolicy(
                    await capture_memory_evidence(await history.snapshot()),
                    {USER: "user", PROJECT: "project"},
                )
                view = await store.open_update()
                assert (
                    await MemoryRuleOptimizer(facade(owner, "rule"), policy).optimize(
                        view, USER, data, batch_id="batch"
                    )
                    == ()
                )
                assert not captured and not selected and not await owner.records.list_requests("s")
            finally:
                await owner.aclose()
    finally:
        await history.aclose()


async def test_new_draft_id_is_stable_after_discard_and_inference_not_yet_adopted(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, permit, e = await setup(tmp_path)
    store.selector = Selector()
    try:
        with protocol_server(
            [
                json_reply("openai", "Explain parameter flow."),
                json_reply("openai", "Explain parameter flow."),
            ]
        ) as (url, _):
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
                policy = MemoryEvidencePolicy(
                    await capture_memory_evidence(await history.snapshot()),
                    {USER: "user", PROJECT: "project"},
                )
                optimizer = MemoryRuleOptimizer(facade(owner, "rule"), policy)
                first = await store.open_update()
                ids = await optimizer.optimize(
                    first, USER, feedback(e, explicit=False), batch_id="batch"
                )
                first.discard()
                second = await store.open_update()
                retry = await optimizer.optimize(
                    second, USER, feedback(e, explicit=False), batch_id="batch"
                )
                assert retry == ids
                report = await MemoryCommitService(history, policy, permit, approve).commit(
                    second, USER
                )
                assert (
                    not report.can_advance
                    and report.rejected_groups[0]["reason"] == "memory_rule_unevaluated"
                )
                assert not store.read(USER)["records"]
            finally:
                await owner.aclose()
    finally:
        await history.aclose()


async def test_unknown_multi_rule_choice_cannot_update_unread_records(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, permit, e = await setup(tmp_path)
    store.selector = Selector()
    try:
        initial = await store.open_update()
        for i in range(2):
            await initial.aput(USER, f"rule-{i}", rule(e, f"Original rule {i}."))
        await (await service(history, permit, approve)).commit(initial, USER)
        with protocol_server(
            [
                skill_reply(
                    "Classify",
                    {"reasoning": "untrusted choice", "which": ["other-project-rule"]},
                    "unknown",
                )
            ]
        ) as (url, captured):
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
                policy = MemoryEvidencePolicy(
                    await capture_memory_evidence(await history.snapshot()),
                    {USER: "user", PROJECT: "project"},
                )
                view = await store.open_update()
                with pytest.raises(ModelError) as error:
                    await MemoryRuleOptimizer(facade(owner, "rule"), policy).optimize(
                        view, USER, feedback(e), batch_id="batch"
                    )
                assert error.value.code == "memory_prompt_mapping_invalid" and len(captured) == 1
                assert all(
                    record["value"]["content"]["body"].startswith("Original rule")
                    for record in store.read(USER)["records"].values()
                )
            finally:
                await owner.aclose()
    finally:
        await history.aclose()


@pytest.mark.parametrize(
    "result", ["", "  ", "api_key=abcdefghijklmnop", "Rule without required variable"]
)
async def test_invalid_optimized_text_does_not_stage_or_overwrite_existing_rule(
    tmp_path, monkeypatch, result
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, permit, e = await setup(tmp_path)
    store.selector = Selector()
    try:
        view = await store.open_update()
        await view.aput(USER, "rule", rule(e, "Explain {argument} with an example."))
        await (await service(history, permit, approve)).commit(view, USER)
        original = (await store.aget(USER, "rule")).value
        with protocol_server([json_reply("openai", result)]) as (url, captured):
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
                policy = MemoryEvidencePolicy(
                    await capture_memory_evidence(await history.snapshot()),
                    {USER: "user", PROJECT: "project"},
                )
                view = await store.open_update()
                with pytest.raises(ToolError):
                    await MemoryRuleOptimizer(facade(owner, "rule"), policy).optimize(
                        view, USER, feedback(e), batch_id="batch"
                    )
                assert (await store.aget(USER, "rule")).value == original
                assert (await view.aget(USER, "rule")).value == original
                assert (
                    len(captured) == 1
                    and (await owner.records.list_requests("s"))[0].usage.input_tokens == 20
                )
            finally:
                await owner.aclose()
    finally:
        await history.aclose()


async def test_optimized_rule_preserves_original_template_variable_and_scope(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, permit, e = await setup(tmp_path)
    store.selector = Selector()
    try:
        initial = await store.open_update()
        await initial.aput(USER, "rule", rule(e, "Explain {argument} with an example."))
        await (await service(history, permit, approve)).commit(initial, USER)
        with protocol_server(
            [json_reply("openai", "Briefly explain the source of {argument}.")]
        ) as (url, _):
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
                policy = MemoryEvidencePolicy(
                    await capture_memory_evidence(await history.snapshot()),
                    {USER: "user", PROJECT: "project"},
                )
                view = await store.open_update()
                assert await MemoryRuleOptimizer(facade(owner, "rule"), policy).optimize(
                    view, USER, feedback(e), batch_id="batch"
                ) == ("rule",)
                assert (await view.aget(USER, "rule")).value["content"][
                    "body"
                ] == "Briefly explain the source of {argument}."
                assert not store.read(PROJECT)["records"]
            finally:
                await owner.aclose()
    finally:
        await history.aclose()
