"""Durable source identity, staged proposals, optimistic versions and owned cancellation."""

import asyncio
import json
import threading

import pytest
from tests.adapters.storage.test_json_memory import PROJECT, USER
from tests.services.test_memory_evidence import proposal, setup

from pycode.adapters.storage.memory_jobs import FileMemoryJobs
from pycode.core.errors import StorageError
from pycode.core.memory_jobs import memory_job


async def fixture(tmp_path):
    history, store, _, entry = await setup(tmp_path)
    snapshot = await history.snapshot()
    job = memory_job(
        USER, snapshot, [entry], {entry: (0, len(snapshot.entries[0].message.text))}, "run", 1
    )
    return history, store, FileMemoryJobs(store.roots), job, entry


async def test_restart_reloads_same_source_and_staged_ids_without_active_content(tmp_path):
    history, store, jobs, job, entry = await fixture(tmp_path)
    try:
        saved = await jobs.create(job)
        running = await jobs.update(saved, now=2, status="running")
        proposed = await jobs.update(
            running,
            now=3,
            status="proposed",
            proposals={"stable": proposal(entry)},
            observed_revision=0,
        )
        restarted = FileMemoryJobs(store.roots)
        pending = await restarted.pending(USER, "s", str(tmp_path))
        assert len(pending) == 1 and pending[0] == proposed
        assert (await restarted.get(USER, job.job_id)).proposals[
            "stable"
        ].content.body == "Always use pytest."
        assert store.read(USER)["revision"] == 0 and not store.read(USER)["records"]
        assert not await restarted.pending(USER, "other", str(tmp_path))
        assert not await restarted.pending(USER, "s", str(tmp_path / "other"))
        assert not await restarted.pending(PROJECT, "s", str(tmp_path))
        assert (await restarted.create(job)) == proposed
    finally:
        await history.aclose()


async def test_job_version_conflicts_and_terminal_state_cannot_be_reopened(tmp_path):
    history, _, jobs, job, _ = await fixture(tmp_path)
    try:
        await jobs.create(job)
        running = await jobs.update(job, now=2, status="running")
        with pytest.raises(StorageError) as error:
            await jobs.update(job, now=3, status="running")
        assert error.value.code == "memory_job_conflict"
        completed = await jobs.update(running, now=4, status="completed")
        assert not await jobs.pending(USER, "s", str(tmp_path))
        with pytest.raises(StorageError) as error:
            await jobs.update(completed, now=5, status="running")
        assert error.value.code == "memory_job_closed"
    finally:
        await history.aclose()


@pytest.mark.parametrize("case", ["duplicate", "namespace", "source", "range", "version"])
async def test_damaged_job_is_reported_and_not_replaced(tmp_path, case):
    history, _, jobs, job, _ = await fixture(tmp_path)
    try:
        await jobs.create(job)
        path = jobs.path(USER, job.job_id)
        value = job.model_dump(mode="json")
        if case == "duplicate":
            payload = path.read_text().replace(
                '"schema_version": 1', '"schema_version": 1, "schema_version": 1'
            )
        else:
            if case == "namespace":
                value["namespace"] = list(PROJECT)
            elif case == "source":
                value["source_leaf_id"] = "invented"
            elif case == "range":
                value["ranges"][job.entry_ids[0]][0] = -1
            else:
                value["schema_version"] = 2
            payload = json.dumps(value)
        path.write_text(payload)
        before = path.read_bytes()
        with pytest.raises(StorageError):
            await jobs.pending(USER, "s", str(tmp_path))
        with pytest.raises(StorageError):
            await jobs.create(job)
        assert path.read_bytes() == before
    finally:
        await history.aclose()


async def test_job_symlink_and_external_scope_are_rejected(tmp_path):
    history, _, jobs, job, _ = await fixture(tmp_path)
    try:
        outside = tmp_path / "outside"
        outside.mkdir()
        root = jobs.root(USER)
        root.mkdir()
        (root / "jobs").symlink_to(outside, target_is_directory=True)
        with pytest.raises(StorageError) as error:
            await jobs.create(job)
        assert error.value.code == "memory_path_invalid"
        assert not list(outside.iterdir())
        with pytest.raises(StorageError):
            await jobs.pending(("memories", "project", "foreign"), "s", str(tmp_path))
    finally:
        await history.aclose()


async def test_cancelled_publication_is_collected_before_task_returns(tmp_path, monkeypatch):
    import pycode.adapters.storage.memory_jobs as module

    history, _, jobs, job, _ = await fixture(tmp_path)
    entered, release = threading.Event(), threading.Event()
    original = module.atomic_write

    def blocked(path, payload):
        entered.set()
        if not release.wait(2):
            raise AssertionError("Test failed to release publication")
        original(path, payload)

    monkeypatch.setattr(module, "atomic_write", blocked)
    task = asyncio.create_task(jobs.create(job))
    try:
        assert await asyncio.to_thread(entered.wait, 2)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 2)
        assert await jobs.get(USER, job.job_id) == job
    finally:
        release.set()
        await asyncio.gather(task, return_exceptions=True)
        await history.aclose()
