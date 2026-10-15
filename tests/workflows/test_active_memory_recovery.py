"""Actual process death restores a committed call without repeating its memory effect."""

import asyncio
import json
import sys
from dataclasses import replace

import pytest
from tests.services.test_json_memory import service_at
from tests.services.test_memory_evidence import proposal
from tests.support.execution import provider
from tests.support.http_provider import protocol_server
from tests.workflows.test_active_memory import current_context
from tests.workflows.test_skills_http import skill_reply

from pycode.adapters.storage.layout import StorageLayout
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.app.config import AppConfig
from pycode.app.container import build_memory_service, build_session_repository, open_agent_session
from pycode.core.errors import StorageError
from pycode.core.messages import ToolCall
from pycode.workflows.memory_tools import memory_tools


def accepted(request):
    return skill_reply(
        "MemoryEvidenceVerdict",
        {
            "supported": True,
            "durable": True,
            "scope_valid": True,
            "deletion_requested": True,
            "reason": "explicit preference",
        },
        "review",
    )


async def test_later_write_between_commit_and_response_cannot_change_original_tool_snapshot(
    tmp_path, monkeypatch
):
    from pycode.services.memory_commit import MemoryCommitService

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server(accepted) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        commit = MemoryCommitService.commit

        async def later_edit(self, *args, **kwargs):
            report = await commit(self, *args, **kwargs)
            if kwargs.get("tool_receipt") is not None:
                await service.edit(report.accepted_ids[0], "Prefer unittest.")
            return report

        monkeypatch.setattr(MemoryCommitService, "commit", later_edit)
        try:
            context = await current_context(history, tmp_path)
            tool = next(t for t in memory_tools(service) if t.spec.name == "manage_user_memory")
            value = proposal(entry)
            value["content"]["sources"][0].update(session_id="current", entry_id="current")
            result = await tool.execute(context, {"action": "create", "content": value})
            assert result.details["memory_records"][0]["content"] == "Always use pytest."
            key = result.details["memory_ids"][0]
            assert (await service.store.aget(service.user_namespace, key)).value["content"][
                "body"
            ] == "Prefer unittest."
            assert len(captured) == 1
        finally:
            await service.aclose()
            await history.aclose()


async def seed(config):
    history = await build_session_repository(config).open("seed", create=True)
    service = build_memory_service(config, history)
    try:
        value = proposal("unused")
        value["content"]["sources"] = []
        await service.command(
            service.user_namespace,
            {"1" * 32: value},
            {"1" * 32: None},
            "Remember permanently: Always use pytest.",
        )
    finally:
        await service.aclose()
        await history.aclose()


@pytest.mark.parametrize("boundary", ["published", "output_ready", "result_saved"])
@pytest.mark.parametrize("action", ["create", "update", "delete"])
async def test_process_exit_restores_exact_manage_result_without_reexecution(
    tmp_path, monkeypatch, boundary, action
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
    if action != "create":
        await seed(config)
    args = {"action": action}
    if action != "create":
        args["id"] = "1" * 32
    if action != "delete":
        body = (
            "Always use pytest." if action == "create" else "Always use pytest with readable tests."
        )
        args["content"] = proposal("current", body, body)
        args["content"]["content"]["sources"][0]["session_id"] = "current"
    calls = []

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        calls.append(names)
        if "MemoryDirectoryChoices" in names:
            return skill_reply("MemoryDirectoryChoices", {"selections": []}, "directory")
        if "MemoryEvidenceVerdict" in names:
            return accepted(request)
        assert "manage_user_memory" in names
        return skill_reply("manage_user_memory", args, "remember-call")

    with protocol_server(respond) as (url, captured):
        child = await asyncio.create_subprocess_exec(
            sys.executable,
            "-m",
            "tests.support.memory_tool_crash_worker",
            str(tmp_path),
            url,
            boundary,
            action,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout, stderr = await asyncio.wait_for(child.communicate(), 15)
            assert child.returncode == 76, (stdout.decode(), stderr.decode())
        finally:
            if child.returncode is None:
                child.kill()
                await child.wait()
        before = len(captured)
        configured = provider(url)
        configured = replace(
            configured, tool_selector=replace(configured.tool_selector, enabled=False)
        )
        restored = await open_agent_session(
            config,
            (configured,),
            "s1",
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        try:
            assert len(captured) == before
            state = await restored._memory.store.snapshot(restored._memory.user_namespace)
            assert len(state["tool_receipts"]) == 1
            assert len(state["records"]) == (0 if action == "delete" else 1)
            snapshot = await restored._store.snapshot()
            results = [
                entry.message.tool_result
                for entry in snapshot.entries
                if entry.message is not None and entry.message.tool_result is not None
            ]
            assert len(results) == 1
            result = results[0]
            assert not result.is_error and result.call_id == "remember-call" and result.output_id
            if boundary != "result_saved":
                assert result.details["memory_operation_recovered"]
            outputs = FileToolOutputStore(
                StorageLayout(tmp_path, config.user_data).sessions, "s1", restored._store
            )
            assert (await outputs.read(result.output_id, 1, 100, 50000)).text == result.text
            assert await restored._memory.store.snapshot(restored._memory.user_namespace) == state
        finally:
            await restored.aclose()
        # A second open must not append another result or rewrite the memory file.
        again = await open_agent_session(
            config,
            (configured,),
            "s1",
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        try:
            assert len(captured) == before
            assert await again._memory.store.snapshot(again._memory.user_namespace) == state
            snapshot = await again._store.snapshot()
            assert (
                sum(
                    e.message is not None and e.message.tool_result is not None
                    for e in snapshot.entries
                )
                == 1
            )
        finally:
            await again.aclose()


async def test_same_operation_replay_uses_original_revision_and_preserves_later_correction(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server(accepted) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        try:
            context = await current_context(history, tmp_path)
            tool = next(t for t in memory_tools(service) if t.spec.name == "manage_user_memory")
            value = proposal(entry)
            value["content"]["sources"][0].update(session_id="current", entry_id="current")
            args = {"action": "create", "content": value}
            first = await tool.execute(context, args)
            key = first.details["memory_ids"][0]
            await service.edit(key, "Prefer unittest.")
            corrected = await service.store.snapshot(service.user_namespace)
            restored = await tool.execute(context, args)
            assert restored.details["memory_operation_recovered"]
            assert restored.details["memory_records"] == first.details["memory_records"]
            assert await service.store.snapshot(service.user_namespace) == corrected
            assert len(captured) == 1
            forged = json.loads(json.dumps(args))
            forged["action"] = "update"
            with pytest.raises(StorageError) as error:
                await service.recover_active_operation(
                    context, ToolCall(context.call_id, tool.spec.name, forged)
                )
            assert error.value.code == "memory_tool_conflict"
        finally:
            await service.aclose()
            await history.aclose()


@pytest.mark.parametrize(
    "case", ["arguments", "record_hash", "missing_backup", "changed_backup", "symlink", "branch"]
)
async def test_corrupt_receipt_or_snapshot_never_replays_or_claims_success(
    tmp_path, monkeypatch, case
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server(accepted) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        try:
            context = await current_context(history, tmp_path)
            tool = next(t for t in memory_tools(service) if t.spec.name == "manage_user_memory")
            value = proposal(entry)
            value["content"]["sources"][0].update(session_id="current", entry_id="current")
            args = {"action": "create", "content": value}
            result = await tool.execute(context, args)
            key = result.details["memory_ids"][0]
            root = service.store.roots[service.user_namespace]
            state = await service.store.snapshot(service.user_namespace)
            identity, receipt = next(iter(state["tool_receipts"].items()))
            backup = root / "versions" / f"revision-{receipt['revision']}.json"
            if case in {"missing_backup", "changed_backup", "symlink"}:
                await service.edit(key, "Prefer unittest.")
                if case == "missing_backup":
                    backup.unlink()
                elif case == "changed_backup":
                    snapshot = json.loads(backup.read_text())
                    snapshot["records"][key]["value"]["content"]["body"] = (
                        "Changed behind the receipt."
                    )
                    backup.write_text(json.dumps(snapshot))
                else:
                    outside = tmp_path / "untrusted-backup.json"
                    outside.write_bytes(backup.read_bytes())
                    backup.unlink()
                    backup.symlink_to(outside)
            elif case == "branch":
                await history.branch(entry)
            else:
                if case == "arguments":
                    state["tool_receipts"][identity]["arguments_sha256"] = "0" * 64
                else:
                    state["tool_receipts"][identity]["record_versions"][key] = "0" * 64
                (root / "memory.json").write_text(json.dumps(state))
            before = (root / "memory.json").read_bytes()
            with pytest.raises(StorageError) as error:
                await service.recover_active_operation(
                    context, ToolCall(context.call_id, tool.spec.name, args)
                )
            assert error.value.code in {
                "memory_tool_conflict",
                "memory_tool_snapshot_missing",
                "memory_path_invalid",
            }
            assert (root / "memory.json").read_bytes() == before and len(captured) == 1
        finally:
            await service.aclose()
            await history.aclose()


async def test_failed_publication_exposes_neither_memory_nor_success_receipt(tmp_path, monkeypatch):
    from pycode.adapters.storage.json_memory import JsonMemoryStore

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server(accepted) as (url, captured):
        history, service, entry = await service_at(tmp_path, provider(url))
        try:
            context = await current_context(history, tmp_path)
            tool = next(t for t in memory_tools(service) if t.spec.name == "manage_user_memory")
            value = proposal(entry)
            value["content"]["sources"][0].update(session_id="current", entry_id="current")
            args = {"action": "create", "content": value}
            publish = JsonMemoryStore.publish

            def fail(self, namespace, state, previous):
                if state["tool_receipts"]:
                    raise OSError("Interrupted atomic publication")
                return publish(self, namespace, state, previous)

            monkeypatch.setattr(JsonMemoryStore, "publish", fail)
            with pytest.raises(StorageError):
                await tool.execute(context, args)
            state = await service.store.snapshot(service.user_namespace)
            assert not state["records"] and not state["tool_receipts"]
            assert (
                await service.recover_active_operation(
                    context, ToolCall(context.call_id, tool.spec.name, args)
                )
                is None
            )
            assert len(captured) == 1
        finally:
            await service.aclose()
            await history.aclose()


@pytest.mark.parametrize("failure", ["result_write", "cancel_after_commit"])
async def test_live_session_recovers_committed_operation_after_write_error_or_cancel(
    tmp_path, monkeypatch, failure
):
    from pycode.core.messages import Message
    from pycode.services.memory_commit import MemoryCommitService

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    committed = asyncio.Event()
    original_commit = MemoryCommitService.commit

    async def hold(self, *args, **kwargs):
        report = await original_commit(self, *args, **kwargs)
        if failure == "cancel_after_commit" and kwargs.get("tool_receipt"):
            committed.set()
            await asyncio.Event().wait()
        return report

    monkeypatch.setattr(MemoryCommitService, "commit", hold)
    args = {"action": "create", "content": proposal("current")}
    args["content"]["content"]["sources"][0]["session_id"] = "current"

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        if "MemoryEvidenceVerdict" in names:
            return accepted(request)
        return skill_reply("manage_user_memory", args, "remember-call")

    with protocol_server(respond) as (url, captured):
        configured = provider(url)
        configured = replace(
            configured, tool_selector=replace(configured.tool_selector, enabled=False)
        )
        config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
        session = await open_agent_session(
            config,
            (configured,),
            "s1",
            create=True,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        append = session._store.append_message
        failed = False

        async def fail_once(message):
            nonlocal failed
            if (
                failure == "result_write"
                and not failed
                and message.tool_result is not None
                and message.tool_result.name == "manage_user_memory"
            ):
                failed = True
                raise StorageError("forced_tool_result_failure", "Cannot publish first result")
            return await append(message)

        session._store.append_message = fail_once
        running = None
        try:
            if failure == "result_write":
                with pytest.raises(StorageError) as error:
                    await session.run(Message("u", "user", "Always use pytest."))
                assert error.value.code == "forced_tool_result_failure"
            else:
                running = asyncio.create_task(
                    session.run(Message("u", "user", "Always use pytest."))
                )
                await asyncio.wait_for(committed.wait(), 3)
                assert await session.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await running
            snapshot = await session._store.snapshot()
            results = [
                entry.message.tool_result
                for entry in snapshot.entries
                if entry.message is not None and entry.message.tool_result is not None
            ]
            assert len(results) == 1 and not results[0].is_error
            assert results[0].details["memory_operation_recovered"] and results[0].output_id
            assert len(captured) == 2
            state = await session._memory.store.snapshot(session._memory.user_namespace)
            assert len(state["records"]) == len(state["tool_receipts"]) == 1
            assert session.pending_run(snapshot) is None
        finally:
            if running is not None and not running.done():
                running.cancel()
                try:
                    await running
                except asyncio.CancelledError:
                    pass
            await session.aclose()
