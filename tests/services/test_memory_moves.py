"""Explicit scope moves keep IDs and recover from each publication boundary."""

import asyncio
import json
import sys
import threading

import pytest
from tests.services.test_json_memory import service_at
from tests.services.test_memory_evidence import proposal

from pycode.app.config import AppConfig
from pycode.app.container import build_memory_service, build_session_repository
from pycode.core.errors import StorageError


async def seed(service, entry, *, scope="user", kind="SemanticMemory"):
    namespace = next(ns for ns, chosen in service.scopes.items() if chosen == scope)
    value = proposal(entry)
    if scope == "project":
        value["content"]["category"] = "project_knowledge"
    if kind == "ProceduralMemory":
        value = {
            "kind": kind,
            "content": {k: v for k, v in value["content"].items() if k != "category"},
        }
        value["content"].update(applicability=["Code explanation"], origin="user_explicit")
    elif kind == "EpisodicMemory":
        value = {
            "kind": kind,
            "content": {k: v for k, v in value["content"].items() if k != "category"},
        }
        value["content"].update(
            situation="Debugging",
            actions=["Selected pytest"],
            outcome="User reported a useful test workflow",
            applicability=["Testing"],
            verification="unverified",
        )
    value["content"]["sources"] = []
    await service.command(
        namespace, {"stable": value}, {"stable": None}, "Remember permanently:\nAlways use pytest."
    )
    return namespace


@pytest.mark.parametrize("scope", ["user", "project"])
@pytest.mark.parametrize("kind", ["SemanticMemory", "ProceduralMemory", "EpisodicMemory"])
async def test_explicit_move_keeps_id_creation_body_and_provenance(tmp_path, scope, kind):
    history, service, entry = await service_at(tmp_path)
    try:
        source = await seed(service, entry, scope=scope, kind=kind)
        target_scope = "project" if scope == "user" else "user"
        original = (await service.store.snapshot(source))["records"]["stable"]
        assert await service.move("stable", target_scope) == "stable"
        target = next(ns for ns, chosen in service.scopes.items() if chosen == target_scope)
        moved = (await service.store.snapshot(target))["records"]["stable"]
        assert not (await service.store.snapshot(source))["records"]
        assert (
            moved["created_at"] == original["created_at"]
            and moved["updated_at"] == original["updated_at"]
        )
        assert moved["value"]["content"]["body"] == original["value"]["content"]["body"]
        assert all(
            s in moved["value"]["content"]["sources"]
            for s in original["value"]["content"]["sources"]
        )
        if kind == "SemanticMemory":
            assert moved["value"]["content"]["category"] == "correction_feedback"
        await service.initialize()
        assert (await service.store.snapshot(target))["records"]["stable"] == moved
    finally:
        await service.aclose()
        await history.aclose()


@pytest.mark.parametrize("boundary", ["target", "source", "audit"])
async def test_restart_completes_each_move_boundary_without_replaying_target_edit(
    tmp_path, monkeypatch, boundary
):
    history, service, entry = await service_at(tmp_path)
    reopened = None
    try:
        source = await seed(service, entry)
        target = next(ns for ns, scope in service.scopes.items() if scope == "project")
        publish = service.moves.publish
        append = history.append_entry
        stopped = False

        def fail_after(namespace, state, previous):
            nonlocal stopped
            publish(namespace, state, previous)
            if not stopped and namespace == (target if boundary == "target" else source):
                stopped = True
                raise OSError("Interrupted after publication")

        async def fail_audit(draft):
            if boundary == "audit" and draft.type == "memory_move_applied":
                raise StorageError("audit_unavailable", "Interrupted after both files saved")
            return await append(draft)

        if boundary != "audit":
            service.moves.publish = fail_after
        history.append_entry = fail_audit
        with pytest.raises((OSError, StorageError)):
            await service.move("stable", "project")
        history.append_entry = append
        await service.edit("stable", "Later target correction.", from_scope="project")
        changed = (await service.store.snapshot(target))["records"]["stable"]
        await service.aclose()
        reopened = build_memory_service(AppConfig(tmp_path, tmp_path / "data"), history)
        await reopened.initialize()
        assert not (await reopened.store.snapshot(source))["records"]
        assert (await reopened.store.snapshot(target))["records"]["stable"] == changed
        journals = await reopened.moves.pending("s", str(tmp_path))
        assert len(journals) == 1 and journals[0].status == "completed"
        snapshot = await history.snapshot()
        assert sum(e.type == "memory_move_applied" for e in snapshot.entries) == 1
    finally:
        if reopened is not None:
            await reopened.aclose()
        await service.aclose()
        await history.aclose()


async def test_target_collision_and_later_source_change_preserve_both_records(tmp_path):
    history, service, entry = await service_at(tmp_path)
    try:
        source = await seed(service, entry)
        target = await seed(service, entry, scope="project")
        before_source = await service.store.snapshot(source)
        before_target = await service.store.snapshot(target)
        with pytest.raises(StorageError) as error:
            await service.move("stable", "project", from_scope="user")
        assert error.value.code == "memory_move_target_conflict"
        assert (await service.store.snapshot(source))["records"] == before_source["records"]
        assert (await service.store.snapshot(target))["records"] == before_target["records"]
    finally:
        await service.aclose()
        await history.aclose()


async def test_edit_scope_is_destination_and_changed_body_keeps_original_id(tmp_path):
    history, service, entry = await service_at(tmp_path)
    try:
        source = await seed(service, entry)
        assert (
            await service.edit("stable", "Project-local explanation style.", scope="project")
            == "stable"
        )
        target = next(ns for ns, scope in service.scopes.items() if scope == "project")
        assert not (await service.store.snapshot(source))["records"]
        assert (await service.store.snapshot(target))["records"]["stable"]["value"]["content"][
            "body"
        ] == "Project-local explanation style."
    finally:
        await service.aclose()
        await history.aclose()


async def test_source_correction_after_copy_is_not_deleted_on_recovery(tmp_path):
    history, service, entry = await service_at(tmp_path)
    reopened = None
    try:
        source = await seed(service, entry)
        target = next(ns for ns, scope in service.scopes.items() if scope == "project")
        publish = service.moves.publish

        def stop(namespace, state, previous):
            publish(namespace, state, previous)
            if namespace == target:
                raise OSError("After target copy")

        service.moves.publish = stop
        with pytest.raises(StorageError):
            await service.move("stable", "project")
        await service.edit("stable", "New source instruction.", from_scope="user")
        corrected = (await service.store.snapshot(source))["records"]["stable"]
        copied = (await service.store.snapshot(target))["records"]["stable"]
        await service.aclose()
        reopened = build_memory_service(AppConfig(tmp_path, tmp_path / "data"), history)
        with pytest.raises(StorageError) as error:
            await reopened.initialize()
        assert error.value.code == "memory_move_source_conflict"
        assert (await reopened.store.snapshot(source))["records"]["stable"] == corrected
        assert (await reopened.store.snapshot(target))["records"]["stable"] == copied
    finally:
        if reopened is not None:
            await reopened.aclose()
        await service.aclose()
        await history.aclose()


async def test_modified_move_journal_is_rejected_without_overwriting_records(tmp_path):
    history, service, entry = await service_at(tmp_path)
    try:
        source = await seed(service, entry)
        await service.move("stable", "project")
        journal = (await service.moves.pending("s", str(tmp_path)))[0]
        path = service.moves.path(journal.move_id)
        value = json.loads(path.read_text())
        value["key"] = "different"
        path.write_text(json.dumps(value))
        with pytest.raises(StorageError) as error:
            await service.moves.pending("s", str(tmp_path))
        assert error.value.code == "memory_move_corrupt"
        assert not (await service.store.snapshot(source))["records"]
    finally:
        await service.aclose()
        await history.aclose()


@pytest.mark.parametrize("boundary", ["project", "user"])
async def test_actual_process_exit_recovers_copy_or_delete_exactly_once(tmp_path, boundary):
    history, service, entry = await service_at(tmp_path)
    source = await seed(service, entry)
    target = next(ns for ns, scope in service.scopes.items() if scope == "project")
    await service.aclose()
    await history.aclose()
    child = await asyncio.create_subprocess_exec(
        sys.executable,
        "-m",
        "tests.support.memory_move_crash_worker",
        str(tmp_path),
        boundary,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(child.communicate(), 10)
        assert child.returncode == 79, (stdout.decode(), stderr.decode())
    finally:
        if child.returncode is None:
            child.kill()
            await child.wait()
    config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
    history = await build_session_repository(config).open("s")
    restored = build_memory_service(config, history)
    try:
        await restored.initialize()
        assert not (await restored.store.snapshot(source))["records"]
        moved = (await restored.store.snapshot(target))["records"]["stable"]
        await restored.initialize()
        assert (await restored.store.snapshot(target))["records"]["stable"] == moved
        assert sum(e.type == "memory_move_applied" for e in (await history.snapshot()).entries) == 1
    finally:
        await restored.aclose()
        await history.aclose()


async def test_cancellation_collects_both_file_operations_before_recovery(tmp_path):
    history, service, entry = await service_at(tmp_path)
    entered, release = threading.Event(), threading.Event()
    reopened = None
    operation = None
    try:
        source = await seed(service, entry)
        target = next(ns for ns, scope in service.scopes.items() if scope == "project")
        publish = service.moves.publish

        def hold(namespace, state, previous):
            publish(namespace, state, previous)
            if namespace == target:
                entered.set()
                if not release.wait(3):
                    raise AssertionError("Move release timeout")

        service.moves.publish = hold
        operation = asyncio.create_task(service.move("stable", "project"))
        assert await asyncio.to_thread(entered.wait, 2)
        operation.cancel()
        await asyncio.sleep(0)
        assert not operation.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await operation
        assert not (await service.store.snapshot(source))["records"]
        await service.aclose()
        reopened = build_memory_service(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), history
        )
        await reopened.initialize()
        assert (await reopened.store.snapshot(target))["records"]["stable"]
        assert sum(e.type == "memory_move_applied" for e in (await history.snapshot()).entries) == 1
    finally:
        release.set()
        if operation is not None and not operation.done():
            operation.cancel()
            try:
                await operation
            except asyncio.CancelledError:
                pass
        if reopened is not None:
            await reopened.aclose()
        await service.aclose()
        await history.aclose()
