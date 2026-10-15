"""Real SDK comparisons issue version-bound permits only after complete, nonregressing evidence."""

import asyncio
import json
import threading

import pytest
from tests.adapters.storage.test_json_memory import USER, value
from tests.services.test_memory_evidence import approve, setup
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_memory_rules import feedback
from tests.workflows.test_memory_store_http import facade
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import (
    build_background_model,
    build_memory_service,
    build_session_repository,
)
from pycode.core.errors import ModelError, StorageError
from pycode.core.memory_evaluation import MemoryRuleEvaluation
from pycode.core.memory_evidence import MemoryEvidencePolicy, MemoryWritePermit
from pycode.services.memory_commit import MemoryCommitService
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.workflows.memory_rule_evaluation import MemoryRuleEvaluator


def rule(entry):
    data = value("Candidate rule.", kind="ProceduralMemory")
    data["content"].update(
        origin="model_evaluated",
        sources=[
            {
                "session_id": "s",
                "entry_id": entry,
                "quote": "Always use pytest.",
            }
        ],
    )
    return data


def responder(case="accepted"):
    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        if "RuleComparisonScore" in names:
            payload = json.loads(request["body"]["messages"][-1]["content"])
            case_id = payload["case_id"]
            a_candidate = payload["answer_a"].startswith("CANDIDATE")
            before, after = (2, 3) if case != "tie" else (3, 3)
            if case_id == "current-instruction":
                before = after = 4
            if case == "regression" and case_id == "mutable-argument":
                before, after = 3, 1
            return skill_reply(
                "RuleComparisonScore",
                {
                    "case_id": case_id,
                    "relevant": case_id
                    in {
                        "parameter-source",
                        "mutable-argument",
                        "minimal-example",
                        "debugging-explanation",
                    }
                    and (case != "uncovered" or case_id == "parameter-source"),
                    "a_score": after if a_candidate else before,
                    "b_score": before if a_candidate else after,
                    "a_constraints_ok": True,
                    "b_constraints_ok": True,
                    "reason": "fixed test judgment",
                },
                "score",
            )
        content = request["body"]["messages"][0]["content"]
        candidate = "Candidate rule." in content
        text = "CANDIDATE concise explanation" if candidate else "BASELINE explanation"
        if request["body"]["messages"][-1]["content"].startswith("Reply with only"):
            text = "4 with extra explanation" if candidate and case == "instruction" else "4"
        if case == "tool" and candidate:
            return skill_reply("Bash", {"command": "echo forbidden"}, "forbidden")
        response = chat_completion()
        response.body = response.body.replace(b'"done"', json.dumps(text).encode())
        return response

    return respond


@pytest.mark.parametrize("case", ["accepted", "tie", "regression", "uncovered", "instruction"])
async def test_sdk_comparison_gates_exact_rule_and_declines_nonimproving_candidate(
    tmp_path, monkeypatch, case
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, _, entry = await setup(tmp_path)
    with protocol_server(responder(case)) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "evaluation",
            "memory_extract",
        )
        try:
            policy = MemoryEvidencePolicy(
                await capture_memory_evidence(await history.snapshot()), {USER: "user"}
            )
            permit = MemoryWritePermit()
            view = await store.open_update(validate_change=permit)
            await view.aput(USER, "candidate", rule(entry))
            evaluator = MemoryRuleEvaluator(
                facade(owner, "eval"),
                policy,
                run_id="evaluation",
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            receipt = evaluator.start(
                view, USER, ["candidate"], "batch", {}, feedback(entry, explicit=False)
            )
            assert await evaluator.run(view, receipt) == (case == "accepted")
            assert receipt.complete and len(receipt.cases) == 6
            assert len(captured) == 18
            if case == "accepted":
                result = await MemoryCommitService(history, policy, permit, approve).commit(
                    view, USER
                )
                assert result.can_advance and (await store.aget(USER, "candidate")) is not None
            else:
                assert not view.preview().changes[USER] and not (
                    await store.aget(USER, "candidate")
                )
        finally:
            await owner.aclose()
            await history.aclose()


async def test_candidate_cannot_execute_business_tool_during_evaluation(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, _, entry = await setup(tmp_path)
    with protocol_server(responder("tool")) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "evaluation",
            "memory_extract",
        )
        try:
            policy = MemoryEvidencePolicy(
                await capture_memory_evidence(await history.snapshot()), {USER: "user"}
            )
            view = await store.open_update()
            await view.aput(USER, "candidate", rule(entry))
            evaluator = MemoryRuleEvaluator(
                facade(owner, "eval"),
                policy,
                run_id="evaluation",
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            receipt = evaluator.start(
                view, USER, ["candidate"], "batch", {}, feedback(entry, explicit=False)
            )
            with pytest.raises(ModelError) as error:
                await evaluator.run(view, receipt)
            assert error.value.code == "component_tool_not_declared"
            assert len(captured) == 2 and not policy.evaluated_rules
        finally:
            await owner.aclose()
            await history.aclose()


async def test_resume_reuses_completed_answers_and_judgments_and_binds_exact_value(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, _, entry = await setup(tmp_path)
    saved = {}
    with protocol_server(responder()) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "evaluation",
            "memory_extract",
        )
        try:
            policy = MemoryEvidencePolicy(
                await capture_memory_evidence(await history.snapshot()), {USER: "user"}
            )
            view = await store.open_update()
            await view.aput(USER, "candidate", rule(entry))

            async def interrupted(prepared, evaluations):
                receipt = next(iter(evaluations.values()))
                saved["receipt"] = receipt
                if len(receipt["cases"]) == 1 and receipt["cases"][0]["judgment"] is not None:
                    raise RuntimeError("simulated process boundary")

            evaluator = MemoryRuleEvaluator(
                facade(owner, "eval"),
                policy,
                run_id="evaluation",
                checkpoint=interrupted,
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            receipt = evaluator.start(
                view, USER, ["candidate"], "batch", {}, feedback(entry, explicit=False)
            )
            with pytest.raises(RuntimeError):
                await evaluator.run(view, receipt)
            assert len(captured) == 3
            resumed = MemoryRuleEvaluation.model_validate_json(json.dumps(saved["receipt"]))
            evaluator = MemoryRuleEvaluator(
                facade(owner, "eval-resumed"),
                policy,
                run_id="evaluation",
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            assert await evaluator.run(view, resumed)
            assert len(captured) == 18
            await view.aput(
                USER,
                "candidate",
                {
                    **rule(entry),
                    "content": {
                        **rule(entry)["content"],
                        "body": "Different untested rule.",
                    },
                },
            )
            with pytest.raises(StorageError) as error:
                await evaluator.run(view, resumed)
            assert error.value.code == "memory_rule_evaluation_target_changed"
            # The earlier authorization also cannot save this altered body.
            permit = MemoryWritePermit()
            report = await MemoryCommitService(history, policy, permit, approve).commit(view, USER)
            assert (
                not report.can_advance
                and report.rejected_groups[0]["reason"] == "memory_rule_unevaluated"
            )
        finally:
            await owner.aclose()
            await history.aclose()


async def test_production_cancel_reopen_resumes_only_unfinished_comparison_steps(
    tmp_path, monkeypatch
):
    from tests.workflows.test_memory_behavior_application import BODY, RULE
    from tests.workflows.test_memory_behavior_application import responder as behavior_responder

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {"block": True}
    entered, release = threading.Event(), threading.Event()
    behavior = behavior_responder(holder, explicit=False)
    compare = responder()
    from pycode.core.messages import Message

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        if "RuleComparisonScore" in names:
            response = compare(request)
            if holder["block"]:
                response.started, response.release = entered, release
            return response
        if "synthetic evaluation task" in request["body"]["messages"][0]["content"]:
            # Distinguish the exact active candidate in this production fixture.
            modified = json.loads(json.dumps(request))
            modified["body"]["messages"][0]["content"] = modified["body"]["messages"][0][
                "content"
            ].replace(RULE, "Candidate rule.")
            return compare(modified)
        return behavior(request)

    with protocol_server(respond) as (url, captured):
        config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
        history = await build_session_repository(config).open("s", create=True)
        holder["entry"] = await history.append_message(Message("u", "user", BODY))
        service = build_memory_service(config, history, provider(url))
        reopened = None
        try:
            await service.initialize()
            await service.settled("run")
            assert await asyncio.to_thread(entered.wait, 3)
            await asyncio.wait_for(service.aclose(), 3)
            pending = await service.jobs.pending(service.user_namespace, "s", str(tmp_path))
            assert len(pending) == 1 and pending[0].error_code == "shutdown"
            assert pending[0].reorganize_reason is None
            saved = next(iter(pending[0].evaluations.values()))
            assert saved.cases[0].before is not None and saved.cases[0].after is not None
            assert saved.cases[0].judgment is None
            assert not service.store.read(service.user_namespace)["records"]
            holder["block"] = False
            release.set()
            reopened = build_memory_service(config, history, provider(url))
            assert await reopened.process(
                await reopened.jobs.get(pending[0].namespace, pending[0].job_id)
            )
            state = await reopened.store.snapshot(reopened.user_namespace)
            assert {record["value"]["kind"] for record in state["records"].values()} == {
                "SemanticMemory",
                "ProceduralMemory",
            }
            completed = await reopened.jobs.get(pending[0].namespace, pending[0].job_id)
            evaluation = next(iter(completed.evaluations.values()))
            assert evaluation.accepted and evaluation.complete and completed.status == "completed"
            assert evaluation.cases[0].before == saved.cases[0].before
            assert evaluation.cases[0].after == saved.cases[0].after
            generation = [
                r
                for r in captured
                if "synthetic evaluation task" in r["body"]["messages"][0]["content"]
            ]
            assert len(generation) == 12
            optimizers = [
                r for r in captured if "new_prompt" in str(r["body"].get("response_format", {}))
            ]
            assert len(optimizers) == 1
        finally:
            release.set()
            if reopened is not None:
                await reopened.aclose()
            await service.aclose()
            await history.aclose()


async def test_receipt_rejects_unconfirmed_request_and_id_only_authority(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, _, entry = await setup(tmp_path)
    with protocol_server(responder()) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "evaluation",
            "memory_extract",
        )
        try:
            evidence = await capture_memory_evidence(await history.snapshot())
            policy = MemoryEvidencePolicy(evidence, {USER: "user"})
            view = await store.open_update()
            await view.aput(USER, "candidate", rule(entry))
            evaluator = MemoryRuleEvaluator(
                facade(owner, "eval"),
                policy,
                run_id="evaluation",
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            receipt = evaluator.start(
                view, USER, ["candidate"], "batch", {}, feedback(entry, explicit=False)
            )
            assert await evaluator.run(view, receipt)
            altered = receipt.model_dump(mode="json")
            altered["cases"][0]["judgment"]["request_id"] = "unconfirmed-request"
            tampered = MemoryRuleEvaluation.model_validate_json(json.dumps(altered))
            fresh = MemoryEvidencePolicy(evidence, {USER: "user"})
            resumed = MemoryRuleEvaluator(
                facade(owner, "eval"),
                fresh,
                run_id="evaluation",
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            with pytest.raises(StorageError) as error:
                await resumed.run(view, tampered)
            assert (
                error.value.code == "memory_rule_evaluation_untrusted" and not fresh.evaluated_rules
            )
            assert len(captured) == 18
            with pytest.raises(ValueError):
                MemoryEvidencePolicy(evidence, {USER: "user"}, evaluated_rule_ids=["candidate"])
            changed_model = MemoryRuleEvaluator(
                facade(owner, "changed"),
                fresh,
                run_id="evaluation",
                model_signature="0" * 64,
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            with pytest.raises(StorageError) as error:
                await changed_model.run(view, receipt)
            assert error.value.code == "memory_rule_evaluation_target_changed"
        finally:
            await owner.aclose()
            await history.aclose()


async def test_unchanged_inferred_body_keeps_original_without_model_requests(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    history, store, _, entry = await setup(tmp_path)
    with protocol_server([]) as (url, captured):
        owner = build_background_model(
            AppConfig(tmp_path, tmp_path / "data"),
            provider(url),
            (),
            "s",
            "evaluation",
            "memory_extract",
        )
        try:
            permit = MemoryWritePermit()
            initial = await store.open_update(validate_change=permit)
            data = rule(entry)
            data["content"]["origin"] = "user_explicit"
            await initial.aput(USER, "existing", data)
            policy = MemoryEvidencePolicy(
                await capture_memory_evidence(await history.snapshot()), {USER: "user"}
            )
            await MemoryCommitService(history, policy, permit, approve).commit(initial, USER)
            before = await store.snapshot(USER)
            view = await store.open_update()
            await view.aput(USER, "new-draft", rule(entry))
            evaluator = MemoryRuleEvaluator(
                facade(owner, "eval"),
                policy,
                run_id="evaluation",
                requests_lookup=lambda: owner.records.list_requests("s"),
            )
            receipt = evaluator.start(
                view,
                USER,
                ["new-draft"],
                "batch",
                before["records"],
                feedback(entry, explicit=False),
            )
            assert receipt.unchanged and receipt.complete
            assert not await evaluator.run(view, receipt)
            assert not view.preview().changes[USER] and not captured
            assert await store.snapshot(USER) == before
        finally:
            await owner.aclose()
            await history.aclose()
