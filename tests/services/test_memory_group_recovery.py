"""Atomic partial receipts skip saved groups and protect newer target edits on restart."""

import pytest
from tests.adapters.storage.test_json_memory import USER
from tests.services.test_memory_evidence import approve, proposal, setup

from pycode.core.errors import StorageError
from pycode.core.memory_evidence import (
    MemoryEvidenceDecision,
    MemoryEvidencePolicy,
    MemoryWritePermit,
)
from pycode.core.messages import Message
from pycode.services.memory_commit import MemoryCommitService, observed_targets
from pycode.services.memory_evidence import capture_memory_evidence


async def coordinator(history, permit, review, snapshot):
    evidence = await capture_memory_evidence(snapshot)
    policy = MemoryEvidencePolicy(evidence, {USER: "user"})
    return MemoryCommitService(history, policy, permit, review), evidence


def progress(evidence):
    source = evidence.texts[0]
    return {
        "session_id": evidence.session_id,
        "branch_id": evidence.leaf_id,
        "entry_ids": [source.entry_id],
        "prefix_hash": evidence.prefix_hash,
        "start": {"entry_id": source.entry_id, "offset": 0},
        "end": {"entry_id": source.entry_id, "offset": len(source.text)},
    }


async def first_partial(history, store, entry):
    snapshot = await history.snapshot()
    proposals = {
        "a": proposal(entry),
        "b": proposal(entry, "Explain argument flow.", "Explain argument flow."),
    }
    initial = await store.snapshot(USER)
    targets = observed_targets(proposals, initial["records"])
    permit = MemoryWritePermit()
    view = await store.open_update(validate_change=permit)
    for key, value in proposals.items():
        await view.aput(USER, key, value)

    async def review(scope, changes, sources):
        return MemoryEvidenceDecision(changes[0]["key"] != "b", True, True)

    commit, evidence = await coordinator(history, permit, review, snapshot)
    result = await commit.commit(view, USER, batch_id="job", progress=progress(evidence))
    assert result.accepted_ids == ("a",) and not result.can_advance
    state = await store.snapshot(USER)
    assert len(state["group_receipts"]) == 1 and not state["processed_batches"]
    return snapshot, proposals, targets


async def test_restart_skips_saved_group_even_after_a_later_user_correction(tmp_path):
    history, store, _, entry = await setup(tmp_path)
    try:
        original, proposals, targets = await first_partial(history, store, entry)
        correction = await history.append_message(Message("correction", "user", "Prefer unittest."))
        permit = MemoryWritePermit()
        edit = await store.open_update(validate_change=permit)
        await edit.aput(USER, "a", proposal(correction, "Prefer unittest.", "Prefer unittest."))
        commit, _ = await coordinator(history, permit, approve, await history.snapshot())
        await commit.commit(edit, USER)
        updated_a = (await store.snapshot(USER))["records"]["a"]
        reviewed = []

        async def retry_review(scope, changes, sources):
            reviewed.extend(item["key"] for item in changes)
            return await approve()

        permit = MemoryWritePermit()
        retry = await store.open_update(validate_change=permit)
        for key, value in proposals.items():
            await retry.aput(USER, key, value)
        commit, evidence = await coordinator(history, permit, retry_review, original)
        result = await commit.commit(
            retry, USER, batch_id="job", progress=progress(evidence), expected_targets=targets
        )
        state = await store.snapshot(USER)
        assert result.can_advance and reviewed == ["b"]
        assert state["records"]["a"] == updated_a
        assert state["records"]["b"]["value"]["content"]["body"] == "Explain argument flow."
        assert len(state["group_receipts"]) == 2 and "job" in state["processed_batches"]
    finally:
        await history.aclose()


async def test_changed_unfinished_target_is_not_overwritten_or_marked_complete(tmp_path):
    history, store, _, entry = await setup(tmp_path)
    try:
        original, proposals, targets = await first_partial(history, store, entry)
        correction = await history.append_message(
            Message("correction", "user", "Explain return values instead.")
        )
        permit = MemoryWritePermit()
        edit = await store.open_update(validate_change=permit)
        await edit.aput(
            USER,
            "b",
            proposal(
                correction, "Explain return values instead.", "Explain return values instead."
            ),
        )
        commit, _ = await coordinator(history, permit, approve, await history.snapshot())
        await commit.commit(edit, USER)
        before = await store.snapshot(USER)
        retry = await store.open_update(validate_change=MemoryWritePermit())
        for key, value in proposals.items():
            await retry.aput(USER, key, value)
        commit, evidence = await coordinator(history, MemoryWritePermit(), approve, original)
        with pytest.raises(StorageError) as error:
            await commit.commit(
                retry, USER, batch_id="job", progress=progress(evidence), expected_targets=targets
            )
        assert error.value.code == "memory_target_conflict"
        assert await store.snapshot(USER) == before
        assert not before["processed_batches"]
    finally:
        await history.aclose()


async def test_publication_failure_leaves_both_content_and_receipts_unpublished(
    tmp_path, monkeypatch
):
    history, store, _, entry = await setup(tmp_path)
    try:
        snapshot = await history.snapshot()
        permit = MemoryWritePermit()
        view = await store.open_update(validate_change=permit)
        await view.aput(USER, "a", proposal(entry))
        commit, evidence = await coordinator(history, permit, approve, snapshot)

        def failed(namespace, state, previous):
            raise OSError("probe fsync failure")

        monkeypatch.setattr(view.parent, "publish", failed)
        with pytest.raises(StorageError):
            await commit.commit(view, USER, batch_id="job", progress=progress(evidence))
        state = await store.snapshot(USER)
        assert (
            not state["records"] and not state["group_receipts"] and not state["processed_batches"]
        )
    finally:
        await history.aclose()


async def test_application_restart_reviews_only_unfinished_group_with_persistent_targets(
    tmp_path, monkeypatch
):
    import json

    from tests.services.test_json_memory import service_at
    from tests.support.execution import provider
    from tests.support.http_provider import protocol_server
    from tests.workflows.test_skills_http import skill_reply

    from pycode.app.config import AppConfig
    from pycode.app.container import build_memory_service
    from pycode.core.memory_jobs import memory_job

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    reviewed = []
    holder = {"reject_b": True}

    def respond(request):
        text = request["body"]["messages"][-1]["content"]
        proposals = json.loads(
            text.split("Changes and observed target records:\n", 1)[1].split(
                "\nTrusted evidence", 1
            )[0]
        )
        key = proposals[0]["key"]
        reviewed.append(key)
        return skill_reply(
            "MemoryEvidenceVerdict",
            {
                "supported": None if key == "b" and holder["reject_b"] else True,
                "durable": True,
                "scope_valid": True,
                "reason": "checked partial group",
            },
            "review",
        )

    with protocol_server(respond) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        restarted = None
        try:
            snapshot = await history.snapshot()
            namespace = service.user_namespace
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
            assert pending.error_code == "memory_evidence_review_invalid"
            assert pending.reorganize_reason is None
            state = await service.store.snapshot(namespace)
            assert set(state["records"]) == {"a"} and not state["processed_batches"]
            await service.aclose()
            holder["reject_b"] = False
            config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
            restarted = build_memory_service(config, history, provider(url))
            # Resume the exact durable job; do not run another source organizer in this test.
            await restarted.ensure_store()
            current = await restarted.jobs.get(namespace, job.job_id)
            assert await restarted.process(current)
            after = await restarted.store.snapshot(namespace)
            assert set(after["records"]) == {"a", "b"}
            assert after["records"]["a"] == state["records"]["a"]
            assert len(after["group_receipts"]) == 2 and job.job_id in after["processed_batches"]
            assert reviewed == ["a", "b", "b"] and len(captured) == 3
            assert (await restarted.jobs.get(namespace, job.job_id)).status == "completed"
        finally:
            if restarted is not None:
                await restarted.aclose()
            await service.aclose()
            await history.aclose()
