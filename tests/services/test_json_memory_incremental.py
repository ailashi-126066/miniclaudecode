"""The sole JSON application service preserves all 22 sources through later failure/cancel."""

import asyncio

import pytest
from tests.services.test_json_memory import service_at
from tests.services.test_memory_evidence import approve, proposal

from pycode.core.errors import ModelError
from pycode.core.memory_evidence import MemoryEvidencePolicy, MemoryWritePermit
from pycode.core.messages import Message
from pycode.core.values import plain_data
from pycode.services.memory_commit import MemoryCommitService
from pycode.services.memory_evidence import capture_memory_evidence


class UpdatingSourceRecorder:
    def __init__(self, first, failure):
        self.first, self.failure = first, failure
        self.calls, self.saved = {}, {}

    async def update(self, history, store, scopes, namespace, snapshot, run_id, **options):
        evidence = await capture_memory_evidence(
            snapshot, entry_ids=options["entry_ids"], ranges=options["ranges"]
        )
        self.calls[namespace] = self.calls.get(namespace, 0) + 1
        if scopes[namespace] == "user" and self.calls[namespace] == 2 and self.failure:
            if self.failure == "cancel":
                raise asyncio.CancelledError
            raise ModelError("temporary_model_failure", "Later batch interrupted")
        permit = MemoryWritePermit()
        policy = MemoryEvidencePolicy(evidence, scopes)
        view = await store.open_update(validate_change=permit)
        if options.get("proposals") is not None:
            view.write_changes(namespace, options["proposals"])
        elif scopes[namespace] == "user" and any(
            t.entry_id == self.first and "Always use pytest." in t.text for t in evidence.texts
        ):
            await view.aput(namespace, "first-preference", proposal(self.first))
        if options.get("before_save"):
            await options["before_save"](view.prepare())
        report = await MemoryCommitService(history, policy, permit, approve).commit(
            view, namespace, batch_id=options["batch_id"], progress=options["progress"]
        )
        self.saved.setdefault(namespace, []).extend(
            (t.entry_id, t.start, t.text) for t in evidence.texts
        )
        return report


@pytest.mark.parametrize("failure", [None, "failure", "cancel"])
async def test_json_service_processes_all_22_sources_once_after_later_batch_failure(
    tmp_path, failure
):
    history, service, first = await service_at(tmp_path)
    try:
        expected = {first: (await history.snapshot()).entries[0].message.text}
        for index in range(21):
            text = f"source {index}: " + "x" * 4000
            entry = await history.append_message(Message(f"u-{index}", "user", text))
            expected[entry] = text
        recorder = UpdatingSourceRecorder(first, failure)
        service.updater = recorder
        await service.settled("first")
        await service.collect()
        if failure:
            before = sum(len(text) for _, _, text in recorder.saved[service.user_namespace])
            assert 0 < before < sum(map(len, expected.values()))
            pending = await service.jobs.pending(service.user_namespace, "s", str(tmp_path))
            assert len(pending) == 1 and pending[0].status == "pending_retry"
            recorder.failure = None
            await service.settled("retry")
            await service.collect()
        actual = {key: "" for key in expected}
        order = []
        for key, start, text in recorder.saved[service.user_namespace]:
            assert start == len(actual[key])
            actual[key] += text
            if key not in order:
                order.append(key)
        assert actual == expected and order == list(expected)
        assert not await service.jobs.pending(service.user_namespace, "s", str(tmp_path))
        state = await service.store.snapshot(service.user_namespace)
        assert len(state["records"]) == 1
        assert (
            state["records"]["first-preference"]["value"]["content"]["body"] == "Always use pytest."
        )
        saved = plain_data(recorder.saved)
        await service.settled("nothing-new")
        await service.collect()
        assert plain_data(recorder.saved) == saved
    finally:
        await service.aclose()
        await history.aclose()
