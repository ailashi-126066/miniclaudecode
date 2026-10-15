from dataclasses import replace

import pytest

from pycode.kernel.contracts.messages import UsageRecord
from pycode.kernel.errors import StorageError
from pycode.models.adapters.storage.requests import SQLiteRequestStore
from pycode.models.domain.requests import RequestRecord


def initial_record():
    return RequestRecord(
        "request-1", "session-1", "run-1", "provider", "model", "agent", 0, "started", 1000
    )


async def test_request_attempt_and_usage_survive_close_and_reopen(tmp_path):
    path = tmp_path / "runtime.sqlite"
    store = SQLiteRequestStore(path)
    record = initial_record()
    await store.start(record)
    usage = UsageRecord("provider", "model", "request-1", input_tokens=12, output_tokens=3)
    completed = replace(record, status="completed", settled_at=1001, usage=usage)
    await store.settle(completed)
    await store.settle(completed)
    await store.aclose()
    reopened = SQLiteRequestStore(path)
    try:
        assert await reopened.list_requests("session-1") == (completed,)
        assert await reopened.list_requests("other-session") == ()
        with pytest.raises(StorageError, match="different settled"):
            await reopened.settle(replace(completed, error_code="different"))
    finally:
        await reopened.aclose()


async def test_settlement_cannot_create_a_phantom_request(tmp_path):
    store = SQLiteRequestStore(tmp_path / "runtime.sqlite")
    try:
        with pytest.raises(StorageError, match="not recorded"):
            await store.settle(replace(initial_record(), status="failed"))
    finally:
        await store.aclose()


async def test_unsettled_attempt_remains_explicit_for_later_recovery(tmp_path):
    path = tmp_path / "runtime.sqlite"
    store = SQLiteRequestStore(path)
    await store.start(initial_record())
    await store.aclose()
    reopened = SQLiteRequestStore(path)
    try:
        assert (await reopened.list_requests())[0].status == "started"
        assert (await reopened.list_requests())[0].usage is None
    finally:
        await reopened.aclose()


async def test_settlement_cannot_change_the_recorded_attempt_identity(tmp_path):
    store = SQLiteRequestStore(tmp_path / "runtime.sqlite")
    try:
        await store.start(initial_record())
        with pytest.raises(StorageError, match="identity"):
            await store.settle(replace(initial_record(), status="completed", session_id="other"))
    finally:
        await store.aclose()
