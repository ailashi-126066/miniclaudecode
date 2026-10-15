"""Application-owned official manager reaches one validated JSON commit and progress."""

import json
from dataclasses import replace

import pytest
from tests.adapters.storage.test_json_memory import PROJECT, USER, value
from tests.services.test_memory_evidence import proposal, setup
from tests.support.execution import provider
from tests.support.http_provider import anthropic_message, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.app.background_models import ConfiguredMemoryUpdater
from pycode.app.config import AppConfig
from pycode.app.container import build_background_model, build_memory_update_store
from pycode.core.errors import ModelError, ToolError
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.workflows.memory_update import FactMemoryStore


def sdk_reply(name, arguments, call_id, kind):
    if kind != "anthropic":
        return skill_reply(name, arguments, call_id)
    response = anthropic_message(tool=True)
    response.body = (
        response.body.decode()
        .replace('"name": "echo"', json.dumps("name") + ": " + json.dumps(name))
        .replace(json.dumps('{"value":"wire"}'), json.dumps(json.dumps(arguments)))
        .encode()
    )
    return response


@pytest.mark.parametrize("kind", ["openai", "deepseek", "anthropic"])
@pytest.mark.parametrize("case", ["accepted", "long_source", "unsupported", "fake_source"])
async def test_official_manager_review_publish_and_contiguous_receipt(
    tmp_path, monkeypatch, kind, case
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    text = "Always use pytest. Explain argument flow."
    if case == "long_source":
        text += "\n" + "context " * 800
    history, store, _, entry_id = await setup(tmp_path, text=text)
    accepted = case in {"accepted", "long_source"}
    datum = proposal(entry_id)
    if case == "fake_source":
        datum["content"]["sources"][0]["entry_id"] = "invented"

    def respond(request):
        names = {
            tool["name"] if kind == "anthropic" else tool["function"]["name"]
            for tool in request["body"]["tools"]
        }
        if "MemoryFeedbackChoices" in names:
            return sdk_reply("MemoryFeedbackChoices", {"feedback": []}, "feedback", kind)
        if "MemoryEvidenceVerdict" in names:
            return sdk_reply(
                "MemoryEvidenceVerdict",
                {
                    "supported": accepted,
                    "durable": True,
                    "scope_valid": True,
                    "reason": "source supports preference",
                },
                "review",
                kind=kind,
            )
        assert "SemanticMemory" in names and "ProceduralMemory" not in names
        return sdk_reply("SemanticMemory", datum["content"], "fact", kind)

    with protocol_server(respond) as (url, captured):
        selected = replace(
            provider(url),
            implementation=kind,
            openai_api="chat_completions" if kind == "openai" else None,
        )

        def factory(configured, session, run, purpose):
            owner = build_background_model(
                AppConfig(tmp_path, tmp_path / "data"), configured, (), session, run, purpose
            )
            return owner

        try:
            snapshot = await history.snapshot()
            evidence = await capture_memory_evidence(snapshot)
            progress = {
                "session_id": "s",
                "branch_id": snapshot.leaf_id,
                "entry_ids": [entry_id],
                "prefix_hash": evidence.prefix_hash,
                "start": {"entry_id": entry_id, "offset": 0},
                "end": {"entry_id": entry_id, "offset": len(evidence.texts[0].text)},
            }
            result = await ConfiguredMemoryUpdater(
                selected, factory, build_memory_update_store
            ).update(
                history,
                store,
                {USER: "user", PROJECT: "project"},
                USER,
                snapshot,
                "job",
                entry_ids=[entry_id],
                batch_id="batch",
                progress=progress,
            )
            state = store.read(USER)
            assert result.can_advance == accepted
            assert bool(state["records"]) == accepted
            assert bool(state["processed_batches"]) == accepted
            assert store.read(PROJECT)["revision"] == 0
            assert len(captured) == (1 if case == "fake_source" else 3)
            if accepted:
                record = next(iter(state["records"].values()))
                assert record["value"]["content"]["sources"][0]["entry_id"] == entry_id
        finally:
            await history.aclose()


@pytest.mark.parametrize(
    "key", ["rule", "0123456789ab4def8123456789abcdef", "01234567-89ab-4def-8123-456789abcdef"]
)
async def test_fact_manager_cannot_access_rules_or_other_scope(tmp_path, key):
    history, store, _, entry_id = await setup(tmp_path)
    try:
        view = await store.open_update()
        rule = value(kind="ProceduralMemory")
        stored_key = key.replace("-", "")
        await view.aput(USER, stored_key, rule)
        restricted = FactMemoryStore(view, USER)
        for action in ("get", "delete", "update"):
            with pytest.raises(ToolError) as error:
                if action == "get":
                    await restricted.aget(USER, key)
                elif action == "delete":
                    await restricted.adelete(USER, key)
                else:
                    await restricted.aput(USER, key, rule)
            assert error.value.code == "memory_rule_requires_optimizer"
        with pytest.raises(ToolError) as error:
            await restricted.asearch(PROJECT, query="facts")
        assert error.value.code == "memory_scope_denied"
        assert not await restricted.asearch(USER)
        view.discard()
    finally:
        await history.aclose()


async def test_internal_schema_repair_call_budget_stops_before_another_sdk_request(
    tmp_path, monkeypatch
):
    from tests.workflows.test_memory_store_http import facade
    from tests.workflows.test_native_structured_memory import RuleOutput

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([skill_reply("RuleOutput", {"rule": "first"}, "first")]) as (
        url,
        requests,
    ):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"), provider(url), (), "s", "job", "memory_extract"
        )
        try:
            model = facade(owner, "bounded").model_copy(update={"max_calls": 1})
            structured = model.with_structured_output(RuleOutput)
            assert await structured.ainvoke("rule") == {"rule": "first"}
            with pytest.raises(ModelError) as error:
                await structured.ainvoke("another rule")
            assert error.value.code == "component_call_budget"
            assert len(requests) == len(await owner.records.list_requests("s")) == 1
        finally:
            await owner.aclose()


@pytest.mark.parametrize("case", ["selected", "unrelated", "invalid"])
async def test_application_recall_projects_scoped_versions_and_records_actual_selection(
    tmp_path, monkeypatch, case
):
    from tests.adapters.storage.test_json_memory import make_store
    from tests.workflows.test_memory_store_http import reply

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    store, _, _ = make_store(tmp_path)
    await store.aput(USER, "same-id", value())
    await store.aput(PROJECT, "same-id", value(kind="EpisodicMemory"))
    choices = []
    if case == "selected":
        choices = [
            {"id": json.dumps([USER, "same-id"]), "score": 0.95},
            {"id": json.dumps([PROJECT, "same-id"]), "score": 0.8},
        ]
    elif case == "invalid":
        choices = [{"id": "foreign", "score": 0.99}]
    config = AppConfig(tmp_path, tmp_path / "data")
    with protocol_server(lambda request: reply(request, {"selections": choices})) as (
        url,
        captured,
    ):
        owners = []

        def factory(selected, session, run, purpose):
            owner = build_background_model(config, selected, (), session, run, purpose)
            owners.append(owner)
            return owner

        updater = ConfiguredMemoryUpdater(provider(url), factory, build_memory_update_store)
        if case == "invalid":
            with pytest.raises(ToolError):
                await updater.recall(
                    store, {USER: "user", PROJECT: "project"}, "s", "recall", "参数"
                )
        else:
            records = await updater.recall(
                store, {USER: "user", PROJECT: "project"}, "s", "recall", "参数"
            )
            assert len(records) == (2 if case == "selected" else 0)
            if records:
                assert records[0].namespace != records[1].namespace
                assert {record.kind for record in records} == {"SemanticMemory", "EpisodicMemory"}
                assert all(
                    record.memory_id == "same-id" and len(record.version) == 64
                    for record in records
                )
        assert len(captured) == 1
        assert store.read(USER)["revision"] == store.read(PROJECT)["revision"] == 1
        assert len(owners) == 1
        from pycode.adapters.storage.layout import StorageLayout
        from pycode.adapters.storage.requests import SQLiteRequestStore

        ledger = SQLiteRequestStore(
            StorageLayout(config.workspace, config.user_data).runtime_database
        )
        try:
            requests = await ledger.list_requests("s")
            assert len(requests) == 1 and requests[0].purpose == "memory_select"
            assert requests[0].logical_id == "memory_recall:0"
            assert requests[0].usage.input_tokens == 20 and requests[0].usage.output_tokens == 5
        finally:
            await ledger.aclose()


@pytest.mark.parametrize("method", [None, "json_schema", "function_calling"])
async def test_deepseek_thinking_uses_compatible_auto_choice_with_native_schema_parser(
    tmp_path, monkeypatch, method
):
    from tests.workflows.test_native_structured_memory import RuleOutput

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    from tests.workflows.test_native_structured_memory import structured_reply

    response = (
        structured_reply("openai", {"rule": "bounded"})
        if method is None
        else skill_reply("RuleOutput", {"rule": "bounded"}, "rule")
    )
    with protocol_server([response]) as (
        url,
        captured,
    ):
        configured = replace(
            provider(url),
            implementation="deepseek",
            openai_api=None,
            supports_thinking=True,
            thinking={"type": "enabled"},
        )
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"), configured, (), "s", "job", "memory_extract"
        )
        try:
            updater = ConfiguredMemoryUpdater(configured, None, build_memory_update_store)
            options = {"method": method} if method else {}
            result = (
                await updater.facade(owner, "schema")
                .with_structured_output(RuleOutput, **options)
                .ainvoke("Create a rule using the schema tool.")
            )
            assert result == {"rule": "bounded"}
            assert captured[0]["body"]["thinking"] == {"type": "enabled"}
            if method is None:
                assert captured[0]["body"]["response_format"] == {"type": "json_object"}
                assert (
                    "tool_choice" not in captured[0]["body"] and "tools" not in captured[0]["body"]
                )
                assert "Return only JSON" in captured[0]["body"]["messages"][0]["content"]
            else:
                assert captured[0]["body"]["tool_choice"] == "auto"
            assert len(await owner.records.list_requests("s")) == 1
        finally:
            await owner.aclose()


async def test_official_manager_can_rebind_remove_schema_with_existing_records(
    tmp_path, monkeypatch
):
    from uuid import NAMESPACE_DNS, uuid5

    from tests.adapters.storage.test_json_memory import make_store
    from tests.workflows.test_memory_store_http import reply

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, _, _, entry = await setup(tmp_path)
    store, _, _ = make_store(tmp_path)
    await store.aput(USER, "existing", proposal(entry))

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"]["tools"]}
        if "MemoryFeedbackChoices" in names:
            return skill_reply("MemoryFeedbackChoices", {"feedback": []}, "feedback")
        if "MemoryDirectoryChoices" in names:
            return reply(
                request, {"selections": [{"id": json.dumps([USER, "existing"]), "score": 1.0}]}
            )
        if "MemoryEvidenceVerdict" in names:
            return skill_reply(
                "MemoryEvidenceVerdict",
                {
                    "supported": True,
                    "durable": True,
                    "scope_valid": True,
                    "reason": "supported correction",
                },
                "review",
            )
        assert "RemoveDoc" in names
        return skill_reply(
            "PatchDoc",
            {
                "json_doc_id": uuid5(NAMESPACE_DNS, str((*USER, "existing"))).hex,
                "planned_edits": "Keep the existing identity while clarifying the preference.",
                "patches": [
                    {
                        "op": "replace",
                        "path": "/body",
                        "value": "Always use pytest. Explain argument flow.",
                    }
                ],
            },
            "patch",
        )

    with protocol_server(respond) as (url, captured):

        def owner_factory(configured, session, run, purpose):
            return build_background_model(
                AppConfig(tmp_path, tmp_path / "data"), configured, (), session, run, purpose
            )

        try:
            result = await ConfiguredMemoryUpdater(
                provider(url), owner_factory, build_memory_update_store
            ).update(
                history,
                store,
                {USER: "user", PROJECT: "project"},
                USER,
                await history.snapshot(),
                "update-existing",
                entry_ids=[entry],
            )
            assert result.can_advance and result.accepted_ids == ("existing",)
            assert len(captured) == 4 and len(await store.asearch(USER)) == 1
        finally:
            await history.aclose()
