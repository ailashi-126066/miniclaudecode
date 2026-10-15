"""Real JSONL branches and checked output prove identity; semantic review proves support."""

import asyncio

import pytest
from tests.adapters.storage.test_json_memory import PROJECT, USER, progress, value
from tests.support.requests import TestTiming

from pycode.adapters.storage.json_memory import JsonMemoryStore
from pycode.adapters.storage.session import JsonlSessionStore
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.core.errors import ModelError, StorageError, ToolError
from pycode.core.memory_evidence import (
    MemoryEvidenceDecision,
    MemoryEvidencePolicy,
    MemoryWritePermit,
)
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.session import SessionHeader
from pycode.core.tools import ToolContext
from pycode.services.memory_commit import MemoryCommitService
from pycode.services.memory_evidence import capture_memory_evidence


async def setup(tmp_path, text="Always use pytest. Explain argument flow."):
    timing = TestTiming()
    history = JsonlSessionStore(
        tmp_path / "sessions" / "s", SessionHeader("s", str(tmp_path), "t"), timing
    )
    entry_id = await history.append_message(Message("u", "user", text))
    permit = MemoryWritePermit()

    async def select(*args):
        return []

    store = JsonMemoryStore(
        {USER: tmp_path / "user", PROJECT: tmp_path / "project"},
        selector=select,
        validate_change=permit,
    )
    return history, store, permit, entry_id


def proposal(entry_id, body="Always use pytest.", quote="Always use pytest."):
    data = value(body)
    data["content"]["sources"] = [{"session_id": "s", "entry_id": entry_id, "quote": quote}]
    return data


async def service(
    history,
    permit,
    review,
    *,
    entry_ids=None,
    output_reader=None,
    ranges=None,
    evaluated_rule_ids=(),
):
    evidence = await capture_memory_evidence(
        await history.snapshot(), entry_ids=entry_ids, output_reader=output_reader, ranges=ranges
    )
    policy = MemoryEvidencePolicy(
        evidence, {USER: "user", PROJECT: "project"}, evaluated_rule_ids=evaluated_rule_ids
    )
    return MemoryCommitService(history, policy, permit, review)


async def approve(*args):
    return MemoryEvidenceDecision(True, True, True, deletion_requested=True, outcome_verified=True)


async def test_global_user_preference_cannot_be_committed_to_project_scope(tmp_path):
    history, store, permit, entry = await setup(tmp_path)
    try:
        view = await store.open_update()
        await view.aput(PROJECT, "misplaced", proposal(entry))
        coordinator = await service(history, permit, approve)
        report = await coordinator.commit(view, PROJECT)
        assert (
            not report.can_advance and report.rejected_groups[0]["reason"] == "memory_scope_denied"
        )
        assert not store.read(PROJECT)["records"]
    finally:
        await history.aclose()


async def test_write_permit_is_required_and_exactly_bound_to_reviewed_content(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    try:
        with pytest.raises(ToolError) as error:
            await store.aput(USER, "unvalidated", proposal(e))
        assert error.value.code == "memory_write_unvalidated"
        view = await store.open_update()
        await view.aput(USER, "valid", proposal(e))
        coordinator = await service(history, permit, approve)
        report = await coordinator.commit(view, USER)
        assert report.accepted_ids == ("valid",) and report.can_advance
        assert (await store.aget(USER, "valid")).value["content"]["body"] == "Always use pytest."
        with pytest.raises(ToolError):
            await store.aput(USER, "valid", proposal(e, "unsupported rewrite"))
    finally:
        await history.aclose()


@pytest.mark.parametrize(
    "failure",
    ["unknown", "foreign_session", "wrong_quote", "assistant", "system", "scope", "no_sources"],
)
async def test_physical_evidence_rejection_prevents_model_and_save(tmp_path, failure):
    history, store, permit, e = await setup(tmp_path)
    calls = []

    async def review(*args):
        calls.append(args)
        return await approve()

    try:
        if failure in {"assistant", "system"}:
            e = await history.append_message(Message("other", failure, "Always use pytest."))
        p = proposal(e)
        if failure == "unknown":
            p["content"]["sources"][0]["entry_id"] = "missing"
        elif failure == "foreign_session":
            p["content"]["sources"][0]["session_id"] = "foreign"
        elif failure == "wrong_quote":
            p["content"]["sources"][0]["quote"] = "Use unittest instead."
        elif failure == "scope":
            p["content"]["category"] = "project_knowledge"
        elif failure == "no_sources":
            p["content"]["sources"][0]["entry_id"] = "not-in-range"
        view = await store.open_update()
        await view.aput(USER, "bad", p)
        coordinator = await service(history, permit, review)
        report = await coordinator.commit(view, USER, batch_id="batch", progress=progress())
        assert not report.can_advance and report.rejected_groups and not calls
        assert not store.read(USER)["records"] and not store.read(USER)["processed_batches"]
    finally:
        await history.aclose()


@pytest.mark.parametrize(
    "decision",
    [
        MemoryEvidenceDecision(False, True, True),
        MemoryEvidenceDecision(True, False, True),
        MemoryEvidenceDecision(True, True, False),
    ],
)
async def test_real_quote_does_not_authorize_unsupported_temporary_or_wrong_scope_claim(
    tmp_path, decision
):
    history, store, permit, e = await setup(tmp_path)

    async def reject(*args):
        return decision

    try:
        view = await store.open_update()
        await view.aput(USER, "bad", proposal(e, "Always use unittest."))
        report = await (await service(history, permit, reject)).commit(view, USER)
        assert (
            not report.accepted_ids
            and report.rejected_groups[0]["reason"] == "memory_claim_unsupported"
        )
        assert not store.read(USER)["records"]
    finally:
        await history.aclose()


async def test_invalid_independent_group_does_not_discard_valid_group_or_advance_full_batch(
    tmp_path,
):
    history, store, permit, e = await setup(tmp_path)
    e2 = await history.append_message(Message("u2", "user", "For this task only, use unittest."))

    async def review(scope, changes, sources):
        return MemoryEvidenceDecision(True, changes[0]["key"] != "temporary", True)

    try:
        view = await store.open_update()
        await view.aput(USER, "valid", proposal(e))
        await view.aput(
            USER, "temporary", proposal(e2, "Use unittest.", "For this task only, use unittest.")
        )
        report = await (await service(history, permit, review)).commit(
            view, USER, batch_id="batch", progress=progress()
        )
        assert report.accepted_ids == ("valid",) and not report.can_advance
        assert await store.aget(USER, "valid") and await store.aget(USER, "temporary") is None
        assert not store.read(USER)["processed_batches"]
    finally:
        await history.aclose()


async def test_shared_evidence_group_is_atomic_and_sha_spelling_does_not_split_it(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    from pycode.core.memory import content_hash

    calls = []

    async def reject(scope, changes, sources):
        calls.append(changes)
        return MemoryEvidenceDecision(False, True, True)

    try:
        view = await store.open_update()
        first = proposal(e)
        second = proposal(e, "Explain pytest output.")
        second["content"]["sources"][0]["sha256"] = content_hash("Always use pytest.")
        await view.aput(USER, "one", first)
        await view.aput(USER, "two", second)
        report = await (await service(history, permit, reject)).commit(view, USER)
        assert len(calls) == 1 and len(calls[0]) == 2
        assert tuple(report.rejected_groups[0]["keys"]) == ("one", "two")
        assert not store.read(USER)["records"]
    finally:
        await history.aclose()


async def test_duplicate_content_merges_new_source_into_original_stable_key(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    try:
        view = await store.open_update()
        await view.aput(USER, "original", proposal(e))
        await (await service(history, permit, approve)).commit(view, USER)
        first = await store.aget(USER, "original")
        e2 = await history.append_message(Message("u2", "user", "Always use pytest."))
        view = await store.open_update()
        await view.aput(USER, "new-native-id", proposal(e2))
        report = await (await service(history, permit, approve)).commit(view, USER)
        assert report.accepted_ids == ("original",)
        items = await store.asearch(USER)
        assert (
            len(items) == 1
            and items[0].key == "original"
            and items[0].created_at == first.created_at
        )
        assert {s["entry_id"] for s in items[0].value["content"]["sources"]} == {e, e2}
    finally:
        await history.aclose()


@pytest.mark.parametrize("allowed", [False, True])
async def test_delete_requires_current_user_evidence_and_semantic_target_authorization(
    tmp_path, allowed
):
    history, store, permit, e = await setup(tmp_path)
    try:
        view = await store.open_update()
        await view.aput(USER, "target", proposal(e))
        await (await service(history, permit, approve)).commit(view, USER)
        forget = await history.append_message(
            Message("forget", "user", "Forget my pytest preference.")
        )
        view = await store.open_update()
        await view.adelete(USER, "target")

        async def review(*args):
            return MemoryEvidenceDecision(True, True, True, deletion_requested=allowed)

        report = await (await service(history, permit, review)).commit(
            view,
            USER,
            delete_sources={
                "target": (
                    {
                        "session_id": "s",
                        "entry_id": forget,
                        "quote": "Forget my pytest preference.",
                    },
                )
            },
        )
        assert report.can_advance == allowed
        assert (await store.aget(USER, "target") is None) == allowed
    finally:
        await history.aclose()


async def test_branch_change_during_review_rejects_publication_but_appended_tail_is_allowed(
    tmp_path,
):
    history, store, permit, e = await setup(tmp_path)
    await history.append_message(Message("u2", "user", "Explain argument flow."))
    try:
        view = await store.open_update()
        await view.aput(USER, "valid", proposal(e))

        async def branch(*args):
            await history.branch(e)
            return await approve()

        with pytest.raises(StorageError) as error:
            await (await service(history, permit, branch)).commit(view, USER)
        assert error.value.code == "memory_source_changed" and not store.read(USER)["records"]
        view = await store.open_update()
        await view.aput(USER, "valid", proposal(e))

        async def append(*args):
            await history.append_message(Message("tail", "user", "A new tail"))
            return await approve()

        report = await (await service(history, permit, append)).commit(view, USER)
        assert report.can_advance and await store.aget(USER, "valid")
    finally:
        await history.aclose()


async def test_revision_conflict_does_not_overwrite_concurrent_approved_edit(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    try:
        view = await store.open_update()
        await view.aput(USER, "target", proposal(e))
        await (await service(history, permit, approve)).commit(view, USER)
        pending = await store.open_update()
        await pending.aput(USER, "target", proposal(e, "late stale value"))

        async def concurrent(*args):
            fresh = await store.open_update()
            await fresh.aput(USER, "target", proposal(e, "new approved value"))
            await (await service(history, permit, approve)).commit(fresh, USER)
            return await approve()

        with pytest.raises(StorageError) as error:
            await (await service(history, permit, concurrent)).commit(pending, USER)
        assert error.value.code == "memory_revision_conflict"
        assert (await store.aget(USER, "target")).value["content"]["body"] == "new approved value"
    finally:
        await history.aclose()


@pytest.mark.parametrize("kind", ["inferred_rule", "claimed_case"])
async def test_self_asserted_rule_comparison_or_success_is_not_verification(tmp_path, kind):
    history, store, permit, e = await setup(tmp_path)
    try:
        p = value(kind="ProceduralMemory" if kind == "inferred_rule" else "EpisodicMemory")
        p["content"]["sources"] = proposal(e)["content"]["sources"]
        if kind == "inferred_rule":
            p["content"]["origin"] = "model_evaluated"
        else:
            p["content"]["verification"] = "verified"
        view = await store.open_update()
        await view.aput(USER, "bad", p)
        report = await (await service(history, permit, approve)).commit(view, USER)
        assert not report.can_advance and not store.read(USER)["records"]
        assert report.rejected_groups[0]["reason"] in {
            "memory_rule_unevaluated",
            "memory_outcome_unverified",
        }
    finally:
        await history.aclose()


async def test_full_tool_output_and_bounded_char_range_are_checked_beyond_preview(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    try:
        await history.append_message(
            Message("a", "assistant", tool_calls=(ToolCall("call", "Bash", {"command": "pytest"}),))
        )
        outputs = FileToolOutputStore(tmp_path / "sessions", "s", history)
        body = "noise\n" * 2000 + "17 tests passed.\n" + "tail\n" * 2000
        result = await outputs.prepare(
            ToolContext("s", "r", "op", str(tmp_path), "call", "Bash"),
            ToolResult("call", "Bash", body, exit_code=0),
            "head",
        )
        entry = await history.append_message(Message("t", "tool", tool_result=result))
        await outputs.bind(result.output_id, entry)
        assert "17 tests passed." not in result.text
        evidence = await capture_memory_evidence(
            await history.snapshot(),
            entry_ids=(entry,),
            output_reader=outputs,
            ranges={entry: (12000, 12017)},
        )
        datum = evidence.texts[0]
        assert (
            datum.text == "17 tests passed.\n" and datum.start == 12000 and datum.can_verify_outcome
        )
        policy = MemoryEvidencePolicy(evidence, {USER: "user", PROJECT: "project"})
        p = value(kind="EpisodicMemory")
        p["content"]["verification"] = "verified"
        p["content"]["sources"] = [
            {"session_id": "s", "entry_id": entry, "quote": "17 tests passed."}
        ]
        view = await store.open_update()
        await view.aput(PROJECT, "case", p)
        report = await MemoryCommitService(history, policy, permit, approve).commit(view, PROJECT)
        assert report.can_advance
    finally:
        await history.aclose()


async def test_cancelling_semantic_review_never_issues_write_permit_or_progress(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    started = asyncio.Event()
    cancelled = asyncio.Event()

    async def delayed(*args):
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            cancelled.set()

    try:
        view = await store.open_update()
        await view.aput(USER, "valid", proposal(e))
        coordinator = await service(history, permit, delayed)
        task = asyncio.create_task(
            coordinator.commit(view, USER, batch_id="batch", progress=progress())
        )
        await asyncio.wait_for(started.wait(), 2)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert cancelled.is_set() and not permit.entries
        assert not store.read(USER)["records"] and not store.read(USER)["processed_batches"]
    finally:
        await history.aclose()


async def test_successful_batch_progress_uses_only_real_captured_source_positions(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    try:
        coordinator = await service(history, permit, approve)
        evidence = coordinator.policy.evidence
        source = evidence.texts[0]
        valid = {
            "session_id": "s",
            "branch_id": e,
            "entry_ids": [e],
            "prefix_hash": evidence.prefix_hash,
            "start": {"entry_id": e, "offset": 0},
            "end": {"entry_id": e, "offset": len(source.text)},
        }
        view = await store.open_update()
        await view.aput(USER, "valid", proposal(e))
        report = await coordinator.commit(view, USER, batch_id="batch", progress=valid)
        assert report.can_advance and store.read(USER)["processed_batches"]["batch"]["end"][
            "offset"
        ] == len(source.text)
        view = await store.open_update()
        await view.aput(USER, "bad-progress", proposal(e, "Explain parameter flow."))
        invalid = {**valid, "prefix_hash": "a" * 64}
        with pytest.raises(ToolError) as error:
            await (await service(history, permit, approve)).commit(
                view, USER, batch_id="bad", progress=invalid
            )
        assert error.value.code == "invalid_memory_progress"
        assert await store.aget(USER, "bad-progress") is None
    finally:
        await history.aclose()


async def test_critical_review_storage_failure_propagates_and_regular_group_failure_isolated(
    tmp_path,
):
    history, store, permit, e = await setup(tmp_path)
    e2 = await history.append_message(Message("u2", "user", "Explain argument flow."))
    try:

        async def model_failure(scope, changes, sources):
            if changes[0]["key"] == "bad":
                raise ModelError("model_timeout", "fixture failure")
            return await approve()

        view = await store.open_update()
        await view.aput(USER, "good", proposal(e))
        await view.aput(
            USER, "bad", proposal(e2, "Explain argument flow.", "Explain argument flow.")
        )
        report = await (await service(history, permit, model_failure)).commit(view, USER)
        assert (
            report.accepted_ids == ("good",)
            and report.rejected_groups[0]["reason"] == "model_timeout"
        )
        fatal = StorageError("disk_failed", "cannot record usage")

        async def storage_failure(*args):
            raise fatal

        view = await store.open_update()
        await view.aput(USER, "other", proposal(e2, "Another fact.", "Explain argument flow."))
        with pytest.raises(StorageError) as error:
            await (await service(history, permit, storage_failure)).commit(view, USER)
        assert error.value is fatal and await store.aget(USER, "other") is None
    finally:
        await history.aclose()


async def test_progress_cannot_skip_or_reverse_captured_completed_messages(tmp_path):
    history, store, permit, e1 = await setup(tmp_path)
    e2 = await history.append_message(Message("two", "user", "More durable input."))
    e3 = await history.append_message(Message("three", "user", "Last input."))
    try:
        for ids in ([e1, e3], [e2, e1]):
            coordinator = await service(history, permit, approve)
            evidence = coordinator.policy.evidence
            captured = {source.entry_id: source for source in evidence.texts}
            progress_ = {
                "session_id": "s",
                "branch_id": e1,
                "entry_ids": ids,
                "prefix_hash": evidence.prefix_hash,
                "start": {"entry_id": ids[0], "offset": 0},
                "end": {"entry_id": ids[-1], "offset": len(captured[ids[-1]].text)},
            }
            view = await store.open_update()
            await view.aput(USER, "pending", proposal(e1))
            with pytest.raises(ToolError) as error:
                await coordinator.commit(view, USER, batch_id="batch", progress=progress_)
            assert error.value.code == "invalid_memory_progress"
        assert not store.read(USER)["records"] and not store.read(USER)["processed_batches"]
    finally:
        await history.aclose()


async def test_successful_read_tool_output_does_not_count_as_verified_execution(tmp_path):
    history, _, _, _ = await setup(tmp_path)
    try:
        await history.append_message(
            Message(
                "a",
                "assistant",
                tool_calls=(ToolCall("echo", "echo", {"value": "17 tests passed"}),),
            )
        )
        entry = await history.append_message(
            Message(
                "t", "tool", tool_result=ToolResult("echo", "echo", "17 tests passed", exit_code=0)
            )
        )
        evidence = await capture_memory_evidence(await history.snapshot(), entry_ids=(entry,))
        assert not evidence.texts[0].can_verify_outcome
    finally:
        await history.aclose()


async def test_source_capture_never_falls_back_to_preview_when_full_output_is_corrupt(tmp_path):
    history, _, _, _ = await setup(tmp_path)
    try:
        await history.append_message(
            Message("a", "assistant", tool_calls=(ToolCall("call", "Bash", {"command": "pytest"}),))
        )
        outputs = FileToolOutputStore(tmp_path / "sessions", "s", history)
        result = await outputs.prepare(
            ToolContext("s", "r", "op", str(tmp_path), "call", "Bash"),
            ToolResult("call", "Bash", "line\n" * 3000, exit_code=0),
            "head",
        )
        entry = await history.append_message(Message("t", "tool", tool_result=result))
        await outputs.bind(result.output_id, entry)
        path = tmp_path / "sessions" / "s" / "tool-results" / (result.output_id + ".txt")
        path.write_bytes(b"corrupted object")
        with pytest.raises(ToolError) as error:
            await capture_memory_evidence(
                await history.snapshot(), entry_ids=(entry,), output_reader=outputs
            )
        assert error.value.code == "output_corrupt"
    finally:
        await history.aclose()


async def test_migrated_uuid_delete_source_uses_original_key(tmp_path):
    from uuid import UUID

    history, store, permit, e = await setup(tmp_path)
    key = "a" * 32
    try:
        view = await store.open_update()
        await view.aput(USER, key, proposal(e))
        await (await service(history, permit, approve)).commit(view, USER)
        forget = await history.append_message(
            Message("forget", "user", "Forget my pytest preference.")
        )
        view = await store.open_update()
        await view.adelete(USER, str(UUID(key)))
        sources = {
            str(UUID(key)): (
                {"session_id": "s", "entry_id": forget, "quote": "Forget my pytest preference."},
            )
        }
        report = await (await service(history, permit, approve)).commit(
            view, USER, delete_sources=sources
        )
        assert report.accepted_ids == (key,) and await store.aget(USER, key) is None
    finally:
        await history.aclose()


async def test_source_count_overflow_in_merged_group_does_not_drop_independent_group(tmp_path):
    history, store, permit, e = await setup(tmp_path)
    try:
        sources = []
        for index in range(32):
            quote = f"Fact source {index}."
            source = await history.append_message(Message(f"u-{index}", "user", quote))
            sources.append({"session_id": "s", "entry_id": source, "quote": quote})
        original = proposal(e)
        original["content"]["sources"] = sources
        view = await store.open_update()
        await view.aput(USER, "original", original)
        await (await service(history, permit, approve)).commit(view, USER)
        independent = await history.append_message(
            Message("independent", "user", "Use clear function names.")
        )
        view = await store.open_update()
        await view.aput(USER, "duplicate", proposal(e))
        await view.aput(
            USER,
            "independent",
            proposal(independent, "Use clear function names.", "Use clear function names."),
        )
        report = await (await service(history, permit, approve)).commit(view, USER)
        assert report.accepted_ids == ("independent",) and not report.can_advance
        assert report.rejected_groups[0]["reason"] == "invalid_memory_content"
        assert len((await store.aget(USER, "original")).value["content"]["sources"]) == 32
    finally:
        await history.aclose()
