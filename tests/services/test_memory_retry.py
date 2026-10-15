"""Actual staged writes and SDK recovery revise only unfinished evidence groups."""

import asyncio
import json
import threading

import pytest
from tests.adapters.storage.test_json_memory import USER
from tests.services.test_memory_evidence import approve, proposal, setup
from tests.services.test_memory_group_recovery import coordinator, first_partial, progress

from pycode.core.errors import ToolError
from pycode.core.memory_evidence import MemoryEvidenceText, MemoryWritePermit
from pycode.core.memory_schema import validate_memory_value
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.services.memory_retry import retry_plan, source_segments


@pytest.mark.parametrize(
    "body,done,pending,expected",
    [
        ("ababa 保留", "aba", "保留", [(5, " 保留")]),
        ("参数来源和用途", "参数来源", "来源和用途", [(2, "来源和用途")]),
        ("完整上下文", "absent", "上下文", [(0, "完整上下文")]),
    ],
)
def test_retry_segments_keep_exact_overlapping_spans_without_duplicate_body(
    body, done, pending, expected
):
    text = MemoryEvidenceText("s", "e", "user", body, start=10)

    def source(quote):
        return {"session_id": "s", "entry_id": "e", "quote": quote}

    actual = source_segments(text, [source(done)] * 32, [source(pending)] * 32)
    assert [(datum.start - 10, datum.text) for datum in actual] == expected


async def test_retry_segments_omit_completed_quote_and_guard_canonical_writes(tmp_path):
    history, store, _, entry = await setup(tmp_path)
    try:
        snapshot, values, _ = await first_partial(history, store, entry)
        evidence = await capture_memory_evidence(snapshot)
        state = await store.snapshot(USER)
        plan = retry_plan(evidence, state, values, "job")
        assert set(plan.proposals) == {"b"}
        assert set(plan.completed_proposals) == {"a"}
        assert plan.protected_keys == {"a"}
        assert "Always use pytest." not in "".join(text.text for text in plan.texts)
        assert "Explain argument flow." in "".join(text.text for text in plan.texts)
        plan.validate("b", values["b"], None)
        with pytest.raises(ToolError) as error:
            plan.validate("a", values["a"], state["records"]["a"])
        assert error.value.code == "memory_completed_group_protected"
        with pytest.raises(ToolError) as error:
            plan.validate("duplicate-a", values["a"], None)
        assert error.value.code == "memory_retry_source_invalid"
        # A fresh ID with a completed claim must also be stopped after canonical deduplication.
        permit = MemoryWritePermit()
        view = await store.open_update(validate_change=permit)
        await view.aput(USER, "new-a", values["a"])
        commit, evidence = await coordinator(history, permit, approve, snapshot)
        report = await commit.commit(
            view, USER, batch_id="job", progress=progress(evidence), retry_plan=plan
        )
        assert not report.can_advance
        assert report.rejected_groups[0]["reason"] == "memory_completed_group_protected"
        assert await store.snapshot(USER) == state
    finally:
        await history.aclose()


async def test_invalid_failed_provenance_can_be_repaired_from_remaining_real_context(tmp_path):
    history, store, _, entry = await setup(tmp_path)
    try:
        snapshot, values, _ = await first_partial(history, store, entry)
        values["b"]["content"]["sources"][0]["entry_id"] = "invented"
        evidence = await capture_memory_evidence(snapshot)
        plan = retry_plan(evidence, await store.snapshot(USER), values, "job")
        corrected = proposal(entry, "Explain argument flow.", "Explain argument flow.")
        plan.validate("b", corrected, None)
        with pytest.raises(ToolError):
            plan.validate("b", values["b"], None)
        assert "Always use pytest." not in "".join(t.text for t in plan.texts)
    finally:
        await history.aclose()


@pytest.mark.parametrize("interrupt", [False, True])
async def test_production_restarts_reorganize_bad_group_and_keep_completed_sources(
    tmp_path, monkeypatch, interrupt
):
    from tests.services.test_json_memory import service_at
    from tests.support.execution import provider
    from tests.support.http_provider import protocol_server
    from tests.workflows.test_skills_http import skill_reply

    from pycode.app.config import AppConfig
    from pycode.app.container import build_memory_service
    from pycode.core.memory_jobs import memory_job
    from pycode.services.memory_commit import observed_targets

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {"reject_b": True, "entry": None, "block": interrupt}
    entered, release = threading.Event(), threading.Event()
    organizers, reviewed = [], []

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        text = json.dumps(request["body"]["messages"])
        if "MemoryFeedbackChoices" in names:
            return skill_reply("MemoryFeedbackChoices", {"feedback": []}, "feedback")
        if "MemoryDirectoryChoices" in names:
            return skill_reply("MemoryDirectoryChoices", {"selections": []}, "directory")
        if "MemoryEvidenceVerdict" in names:
            raw = request["body"]["messages"][-1]["content"]
            changes = json.loads(
                raw.split("Changes and observed target records:\n", 1)[1].split(
                    "\nTrusted evidence", 1
                )[0]
            )
            key = changes[0]["key"]
            reviewed.append(key)
            return skill_reply(
                "MemoryEvidenceVerdict",
                {
                    "supported": key == "a" or not holder["reject_b"],
                    "durable": True,
                    "scope_valid": True,
                    "reason": "checked unfinished work",
                },
                "review",
            )
        assert "SemanticMemory" in names and "Retry context" in text
        assert "Always use pytest." not in text
        assert "Explain argument flow." in text
        organizers.append(text)
        body = (
            "Explain every argument and every line."
            if holder["reject_b"]
            else "Explain argument flow."
        )
        response = skill_reply(
            "SemanticMemory",
            proposal(holder["entry"], body, "Explain argument flow.")["content"],
            "reorganize",
        )
        if holder["block"]:
            response.started, response.release = entered, release
        return response

    with protocol_server(respond) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        holder["entry"] = entry
        reopened = None
        try:
            namespace = service.user_namespace
            snapshot = await history.snapshot()
            values = {
                "a": proposal(entry),
                "b": proposal(
                    entry, "Explain every argument and every line.", "Explain argument flow."
                ),
            }
            job = memory_job(
                namespace,
                snapshot,
                [entry],
                {entry: (0, len(snapshot.entries[0].message.text))},
                "run",
                service.timing.now(),
            )
            job = await service.jobs.create(job)
            job = await service.jobs.update(job, now=service.timing.now(), status="running")
            job = await service.jobs.update(
                job,
                now=service.timing.now(),
                status="proposed",
                proposals=values,
                observed_revision=0,
                observed_targets=observed_targets(values, {}),
            )
            assert not await service.process(job)
            original_a = (await service.store.snapshot(namespace))["records"]["a"]
            await service.aclose()
            config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
            reopened = build_memory_service(config, history, provider(url))
            pending = await reopened.jobs.get(namespace, job.job_id)
            assert pending.reorganize_reason == "memory_claim_unsupported"
            if interrupt:
                running = asyncio.create_task(reopened.process(pending))
                assert await asyncio.to_thread(entered.wait, 2)
                running.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await asyncio.wait_for(running, 2)
                interrupted = await reopened.jobs.get(namespace, job.job_id)
                assert interrupted.error_code == "shutdown"
                assert interrupted.reorganize_reason == "memory_claim_unsupported"
                assert interrupted.proposals == pending.proposals
                release.set()
                holder["block"] = False
                await reopened.aclose()
                reopened = build_memory_service(config, history, provider(url))
                pending = await reopened.jobs.get(namespace, job.job_id)
            assert not await reopened.process(pending)
            restaged = await reopened.jobs.get(namespace, job.job_id)
            assert restaged.model_dump(mode="json")["proposals"]["a"] == validate_memory_value(
                values["a"]
            )
            await reopened.aclose()
            holder["reject_b"] = False
            reopened = build_memory_service(config, history, provider(url))
            assert await reopened.process(await reopened.jobs.get(namespace, job.job_id))
            final = await reopened.store.snapshot(namespace)
            assert final["records"]["a"] == original_a
            assert len(final["records"]) == 2 and len(final["group_receipts"]) == 2
            assert job.job_id in final["processed_batches"]
            assert len(organizers) == (3 if interrupt else 2) and reviewed.count("a") == 1
            assert len(reviewed) == 4 and len(captured) == (12 if interrupt else 10)
            completed = await reopened.jobs.get(namespace, job.job_id)
            assert completed.status == "completed" and completed.reorganize_reason is None
        finally:
            release.set()
            if reopened is not None:
                await reopened.aclose()
            await service.aclose()
            await history.aclose()


async def test_target_conflict_reorganizes_against_latest_records_without_undoing_edits(
    tmp_path, monkeypatch
):
    from tests.services.test_json_memory import service_at
    from tests.support.execution import provider
    from tests.support.http_provider import chat_completion, protocol_server
    from tests.workflows.test_skills_http import skill_reply

    from pycode.app.config import AppConfig
    from pycode.app.container import build_memory_service
    from pycode.core.memory_jobs import memory_job
    from pycode.services.memory_commit import observed_targets

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    reviewed, organized = [], []

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        if "MemoryDirectoryChoices" in names:
            return skill_reply("MemoryDirectoryChoices", {"selections": []}, "directory")
        if "MemoryEvidenceVerdict" in names:
            raw = request["body"]["messages"][-1]["content"]
            changes = json.loads(
                raw.split("Changes and observed target records:\n", 1)[1].split(
                    "\nTrusted evidence", 1
                )[0]
            )
            key = changes[0]["key"]
            reviewed.append(key)
            return skill_reply(
                "MemoryEvidenceVerdict",
                {
                    "supported": None if key == "b" else True,
                    "durable": True,
                    "scope_valid": True,
                    "reason": "temporary invalid verdict",
                },
                "review",
            )
        text = json.dumps(request["body"]["messages"])
        assert "Retry context" in text and "memory_target_conflict" in text
        assert "Explain return values instead." in text
        assert "Always use pytest." not in text and "Prefer unittest." not in text
        organized.append(text)
        # The current target is newer than the source: leave it intact and finish the old batch.
        return chat_completion()

    with protocol_server(respond) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        reopened = None
        try:
            namespace = service.user_namespace
            snapshot = await history.snapshot()
            values = {
                "a": proposal(entry),
                "b": proposal(entry, "Explain argument flow.", "Explain argument flow."),
            }
            job = memory_job(
                namespace,
                snapshot,
                [entry],
                {entry: (0, len(snapshot.entries[0].message.text))},
                "run",
                service.timing.now(),
            )
            job = await service.jobs.create(job)
            job = await service.jobs.update(job, now=service.timing.now(), status="running")
            job = await service.jobs.update(
                job,
                now=service.timing.now(),
                status="proposed",
                proposals=values,
                observed_revision=0,
                observed_targets=observed_targets(values, {}),
            )
            assert not await service.process(job)
            pending = await service.jobs.get(namespace, job.job_id)
            assert pending.reorganize_reason is None
            await service.edit("a", "Prefer unittest.")
            current_b = proposal(
                entry, "Explain return values instead.", "Explain return values instead."
            )
            current_b["content"]["sources"] = []
            await service.command(
                namespace,
                {"b": current_b},
                {"b": None},
                "Remember this preference permanently:\nExplain return values instead.",
            )
            before = await service.store.snapshot(namespace)
            assert not await service.process(pending)
            conflicted = await service.jobs.get(namespace, job.job_id)
            assert conflicted.reorganize_reason == "memory_target_conflict"
            assert await service.store.snapshot(namespace) == before
            await service.aclose()
            reopened = build_memory_service(
                AppConfig(tmp_path, tmp_path / "data", allow_delegation=False),
                history,
                provider(url),
            )
            assert await reopened.process(await reopened.jobs.get(namespace, job.job_id))
            final = await reopened.store.snapshot(namespace)
            assert final["records"] == before["records"]
            assert job.job_id in final["processed_batches"]
            assert len(final["group_receipts"]) == 1
            assert reviewed == ["a", "b"] and len(organized) == 1 and len(captured) == 4
            assert (await reopened.jobs.get(namespace, job.job_id)).status == "completed"
        finally:
            if reopened is not None:
                await reopened.aclose()
            await service.aclose()
            await history.aclose()
