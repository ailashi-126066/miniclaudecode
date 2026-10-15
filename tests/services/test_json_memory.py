"""The production composition root uses JSON recall and durable background memory jobs."""

import json

import pytest
from tests.services.test_memory_evidence import proposal
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_memory_service, build_session_repository
from pycode.core.messages import Message
from pycode.core.session_tree import SessionTree
from pycode.services.json_memory import JsonMemoryService


async def service_at(tmp_path, selected=None):
    config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
    history = await build_session_repository(config).open("s", create=True)
    entry = await history.append_message(
        Message("u", "user", "Always use pytest. Explain argument flow.")
    )
    service = build_memory_service(config, history, selected)
    await service.initialize()
    return history, service, entry


def answer(request, service, entry, *, supported=True):
    names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
    if "MemoryFeedbackChoices" in names:
        return skill_reply("MemoryFeedbackChoices", {"feedback": []}, "no-behavior-feedback")
    if "MemoryDirectoryChoices" in names:
        choices = []
        for namespace in service.scopes:
            choices.extend(
                {"id": json.dumps([namespace, key]), "score": 0.9}
                for key in service.store.read(namespace)["records"]
            )
        return skill_reply("MemoryDirectoryChoices", {"selections": choices}, "directory")
    if "MemoryEvidenceVerdict" in names:
        return skill_reply(
            "MemoryEvidenceVerdict",
            {
                "supported": supported,
                "durable": True,
                "scope_valid": True,
                "reason": "checked",
            },
            "review",
        )
    text = json.dumps(request["body"]["messages"])
    if "for the project scope" in text:
        return chat_completion()
    return skill_reply("SemanticMemory", proposal(entry)["content"], "extract")


async def test_production_service_recall_background_receipt_and_manual_edit_share_json(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {}
    with protocol_server(lambda request: answer(request, holder["service"], holder["entry"])) as (
        url,
        captured,
    ):
        history, service, entry = await service_at(tmp_path, provider(url))
        holder.update(service=service, entry=entry)
        try:
            assert isinstance(service, JsonMemoryService)
            assert await service.select("pytest") == ()
            await service.settled("run")
            await service.collect()
            report = await service.list()
            assert len(report["records"]) == 1 and not report["candidates"]
            record = report["records"][0]
            assert record["scope"] == "user" and record["kind"] == "SemanticMemory"
            assert all(service.store.read(ns)["processed_batches"] for ns in service.scopes)
            assert len(captured) == 4
            selected = await service.select("pytest")
            assert len(selected) == 1 and selected[0].memory_id == record["memory_id"]
            before = len(captured)
            await service.edit(record["memory_id"], "Always use pytest with readable functions.")
            changed = (await service.list())["records"][0]
            assert (
                changed["memory_id"] == record["memory_id"]
                and changed["version"] != record["version"]
            )
            assert changed["content"] == "Always use pytest with readable functions."
            assert len(captured) == before
            await service.remove(record["memory_id"])
            assert not (await service.list())["records"]
            assert len(captured) == before
            for namespace in service.scopes:
                assert (service.store.roots[namespace] / "memory.json").is_file()
                assert not (service.store.roots[namespace] / "MEMORY.md").exists()
        finally:
            await service.aclose()
            await history.aclose()


async def test_rejected_proposal_is_persisted_without_whole_source_progress(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {}
    with protocol_server(
        lambda request: answer(request, holder["service"], holder["entry"], supported=False)
    ) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        holder.update(service=service, entry=entry)
        try:
            await service.settled("run")
            await service.collect()
            user = service.user_namespace
            state = service.store.read(user)
            assert not state["records"] and not state["processed_batches"]
            pending = await service.jobs.pending(user, "s", str(tmp_path))
            assert len(pending) == 1 and pending[0].status == "pending_retry"
            assert pending[0].proposals and pending[0].observed_revision == 0
            assert pending[0].error_code == "memory_claim_unsupported"
            before = len(captured)
            await service.settled("retry")
            await service.collect()
            assert len(captured) == before + 3  # Organizer, behavior classification and review.
            from pycode.adapters.storage.layout import StorageLayout
            from pycode.adapters.storage.requests import SQLiteRequestStore

            ledger = SQLiteRequestStore(StorageLayout(tmp_path, tmp_path / "data").runtime_database)
            try:
                requests = await ledger.list_requests("s")
                user_job = pending[0].job_id
                reviews = [
                    r
                    for r in requests
                    if r.run_id == user_job and r.logical_id == "memory_evidence:0"
                ]
                organizers = [
                    r
                    for r in requests
                    if r.run_id == user_job and r.logical_id == "memory_manager:0"
                ]
                assert [r.attempt for r in reviews] == [0, 1]
                assert [r.attempt for r in organizers] == [0, 1]
                assert pending[0].reorganize_reason == "memory_claim_unsupported"
            finally:
                await ledger.aclose()

        finally:
            await service.aclose()
            await history.aclose()


async def test_explicit_mode_off_creates_no_jobs_or_provider_requests(tmp_path):
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    history = await build_session_repository(config).open("s", create=True)
    service = build_memory_service(config, history)
    try:
        await service.initialize()
        await history.append_message(Message("u", "user", "Remember pytest."))
        assert await service.select("pytest") == ()
        await service.settled("run")
        await service.collect()
        assert not service.tasks
        assert all(not root.exists() for root in service.store.roots.values())
    finally:
        await service.aclose()
        await history.aclose()


async def test_recall_error_is_explicit_and_replaces_old_snapshot_with_no_new_memory(tmp_path):
    history, service, _ = await service_at(tmp_path)
    try:
        assert await service.select("pytest") == ()
        path = SessionTree((await history.snapshot()).index).path(
            (await history.snapshot()).leaf_id
        )
        assert path[-2].type == "memory_recall_error"
        assert path[-2].data["reason"] == "memory_model_required"
        assert path[-1].type == "memory_load" and not path[-1].data["records"]
    finally:
        await service.aclose()
        await history.aclose()


async def test_close_preserves_proposals_and_restart_only_retries_review(tmp_path, monkeypatch):
    import asyncio
    import threading

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    entered, release = threading.Event(), threading.Event()
    holder = {"block": True}

    def respond(request):
        response = answer(request, holder["service"], holder["entry"])
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        if holder["block"] and "MemoryEvidenceVerdict" in names:
            response.started, response.release = entered, release
        return response

    with protocol_server(respond) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        holder.update(service=service, entry=entry)
        restarted = None
        try:
            await service.settled("run")
            assert await asyncio.to_thread(entered.wait, 2)
            await asyncio.wait_for(service.aclose(), 2)
            pending = await service.jobs.pending(service.user_namespace, "s", str(tmp_path))
            assert len(pending) == 1 and pending[0].status == "pending_retry"
            assert pending[0].proposals and pending[0].error_code == "shutdown"
            keys = tuple(pending[0].proposals)
            assert not service.store.read(service.user_namespace)["records"]
            holder["block"] = False
            release.set()
            restarted = build_memory_service(
                AppConfig(tmp_path, tmp_path / "data", allow_delegation=False),
                history,
                provider(url),
            )
            holder["service"] = restarted
            await restarted.initialize()
            await restarted.collect()
            state = restarted.store.read(restarted.user_namespace)
            assert tuple(state["records"]) == keys
            assert not await restarted.jobs.pending(restarted.user_namespace, "s", str(tmp_path))
            organizer_calls = [
                request
                for request in captured
                if "SemanticMemory"
                in {tool["function"]["name"] for tool in request["body"].get("tools", [])}
                and "for the project scope" not in json.dumps(request["body"]["messages"])
            ]
            assert len(organizer_calls) == 1
        finally:
            release.set()
            if restarted is not None:
                await restarted.aclose()
            await service.aclose()
            await history.aclose()


async def test_restart_after_content_saved_completes_audit_without_reapplying_user_changes(
    tmp_path, monkeypatch
):
    from pycode.core.errors import StorageError

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {}
    with protocol_server(lambda request: answer(request, holder["service"], holder["entry"])) as (
        url,
        captured,
    ):
        history, service, entry = await service_at(tmp_path, provider(url))
        holder.update(service=service, entry=entry)
        restarted = None

        async def failed_audit(job):
            raise StorageError("audit_unavailable", "Probe interruption after publication")

        service.audit_completed = failed_audit
        try:
            await service.settled("run")
            with pytest.raises(StorageError):
                await service.collect()
            before = service.store.read(service.user_namespace)
            assert len(before["records"]) == len(before["processed_batches"]) == 1
            pending = await service.jobs.pending(service.user_namespace, "s", str(tmp_path))
            user_job = pending[0].job_id
            assert pending[0].status == "pending_retry"
            await service.aclose()
            restarted = build_memory_service(
                AppConfig(tmp_path, tmp_path / "data", allow_delegation=False),
                history,
                provider(url),
            )
            holder["service"] = restarted
            await restarted.initialize()
            await restarted.collect()
            after = restarted.store.read(restarted.user_namespace)
            assert before == after
            snapshot = await history.snapshot()
            audits = [
                e
                for e in snapshot.entries
                if e.type == "memory_extract"
                and e.data.get("job_id") == user_job
                and e.data.get("status") == "completed"
            ]
            assert len(audits) == 1
            user_calls = [
                request
                for request in captured
                if "for the project scope" not in json.dumps(request["body"]["messages"])
            ]
            assert len(user_calls) == 3
        finally:
            if restarted is not None:
                await restarted.aclose()
            await service.aclose()
            await history.aclose()
