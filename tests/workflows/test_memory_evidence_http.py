"""Automatic source support decisions use public SDK schemas and the existing request ledger."""

import json
from dataclasses import replace

import pytest
from tests.adapters.storage.test_json_memory import PROJECT, USER
from tests.services.test_memory_evidence import proposal, setup
from tests.support.execution import provider
from tests.support.http_provider import anthropic_message, protocol_server
from tests.workflows.test_memory_store_http import facade
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_background_model
from pycode.core.memory_evidence import MemoryEvidencePolicy
from pycode.core.requests import RetryPolicy
from pycode.services.memory_commit import MemoryCommitService
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.workflows.memory_evidence import MemoryEvidenceReviewer


def response(verdict, kind):
    if kind == "anthropic":
        reply = anthropic_message(tool=True)
        reply.body = (
            reply.body.decode()
            .replace('"name": "echo"', '"name": "MemoryEvidenceVerdict"')
            .replace(json.dumps('{"value":"wire"}'), json.dumps(json.dumps(verdict)))
            .encode()
        )
        return reply
    return skill_reply("MemoryEvidenceVerdict", verdict, "evidence-verdict")


@pytest.mark.parametrize("kind", ["openai", "deepseek", "anthropic"])
@pytest.mark.parametrize(
    "case", ["supported", "unsupported", "temporary", "wrong_scope", "malformed", "partial"]
)
async def test_sdk_evidence_verdict_is_metered_and_required_before_publication(
    tmp_path, monkeypatch, kind, case
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    verdict = {
        "supported": case != "unsupported",
        "durable": case != "temporary",
        "scope_valid": case != "wrong_scope",
        "deletion_requested": False,
        "outcome_verified": False,
        "reason": "fixture source support decision",
    }
    if case == "malformed":
        verdict["supported"] = "yes"
    reply = response(verdict, kind)
    if case == "partial":
        if kind == "anthropic":
            reply.body = reply.body.replace(
                b'"stop_reason": "tool_use"', b'"stop_reason": "max_tokens"'
            )
        else:
            reply.body = reply.body.replace(
                b'"finish_reason": "tool_calls"', b'"finish_reason": "length"'
            )
    history, store, permit, entry = await setup(tmp_path)
    try:
        with protocol_server([reply]) as (url, captured):
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
                reviewer = MemoryEvidenceReviewer(
                    facade(owner, "memory_evidence"), current_user_text=evidence.current_user_text
                )
                view = await store.open_update()
                await view.aput(USER, "preference", proposal(entry))
                report = await MemoryCommitService(history, policy, permit, reviewer).commit(
                    view, USER
                )
                assert report.can_advance == (case == "supported")
                assert (await store.aget(USER, "preference") is not None) == (case == "supported")
                assert len(captured) == 1
                payload = json.dumps(captured[0]["body"], ensure_ascii=False)
                assert entry in payload and "Always use pytest." in payload
                assert "Latest user input" in payload
                records = await owner.records.list_requests("s")
                assert len(records) == 1 and records[0].logical_id == "memory_evidence:0"
                assert records[0].usage.input_tokens == 20 and records[0].usage.output_tokens == 5
                assert records[0].model_id == configured.model_id
            finally:
                await owner.aclose()
    finally:
        await history.aclose()
