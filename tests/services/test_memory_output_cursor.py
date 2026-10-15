"""Real output objects retain exact bounded positions across disk-backed resumes."""

import json

import pytest
from tests.adapters.storage.test_json_memory import USER
from tests.adapters.tools.test_output_store import save, setup
from tests.services.test_memory_sources import receipt
from tests.support.requests import TestTiming

from pycode.adapters.storage.memory_jobs import FileMemoryJobs
from pycode.adapters.storage.session import JsonlSessionStore
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.core.errors import StorageError, ToolError
from pycode.core.memory_jobs import memory_job
from pycode.core.memory_schema import MemoryOutputPosition, SourceBatch
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.values import json_dumps
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.services.memory_output import advance_output, output_window
from pycode.services.memory_sources import MemorySourcePlanner, MemorySourceWindow


async def test_multimegabyte_unicode_output_reassembles_exactly_after_reader_restarts(tmp_path):
    history, outputs, context = await setup(tmp_path)
    body = ("中文🙂abc\n" * 160000) + "最后一个字符€"
    assert len(body.encode()) > 2 * 1024 * 1024
    try:
        result = await outputs.prepare(context, ToolResult("call-1", "Bash", body), "head")
        await save(history, outputs, result)
        position = MemoryOutputPosition(output_id=result.output_id)
        pieces = []
        for _ in range(100):
            reader = FileToolOutputStore(tmp_path / "sessions", "s1", history)
            text, complete, beginning = await output_window(
                reader,
                result.output_id,
                position.character_offset,
                60001,
                position=json.loads(position.model_dump_json()),
            )
            assert beginning == position and "�" not in text
            pieces.append(text)
            position = advance_output(position, text)
            # Exercise the on-disk JSON representation, not a shared mutable reader cursor.
            position = MemoryOutputPosition.model_validate_json(position.model_dump_json())
            if complete:
                break
        else:
            raise AssertionError("Full output did not finish in bounded windows")
        assert "".join(pieces) == body
        assert position.absolute_byte_offset == len(body.encode())
        assert position.character_offset == len(body)
        assert position.line_offset == body.count("\n") + 1
    finally:
        await history.aclose()


async def test_persisted_late_cursor_skips_more_than_128_pages_and_captures_exact_evidence(
    tmp_path,
):
    history, outputs, context = await setup(tmp_path)
    body = "x" * (7 * 1024 * 1024) + "\n末尾🙂完整结果\n"
    try:
        await history.append_message(
            Message(
                "a", "assistant", tool_calls=(ToolCall("call-1", "Bash", {"command": "pytest"}),)
            )
        )
        result = await outputs.prepare(context, ToolResult("call-1", "Bash", body), "head")
        entry = await save(history, outputs, result)
        snapshot = await history.snapshot()
        start = len(body) - 10
        position = advance_output(MemoryOutputPosition(output_id=result.output_id), body[:start])
        prior = MemorySourceWindow(
            (entry,), {entry: (0, start)}, {}, {entry: position.model_dump(mode="json")}
        )
        batch = SourceBatch.model_validate_json(json_dumps(receipt(snapshot, prior)))
        batch_path = tmp_path / "processed.json"
        batch_path.write_text(batch.model_dump_json())
        await history.aclose()
        history = JsonlSessionStore(tmp_path / "sessions/s1", snapshot.header, TestTiming())
        snapshot = await history.snapshot()
        checked = FileToolOutputStore(tmp_path / "sessions", "s1", history)
        reads = []

        class TrackingReader:
            async def read(self, *args, **kwargs):
                reads.append(kwargs["absolute_byte_offset"])
                return await checked.read(*args, **kwargs)

        batches = {"prior": json.loads(batch_path.read_text())}
        planner = MemorySourcePlanner(output_reader=TrackingReader(), token_budget=800)
        window = await planner.next(snapshot, batches)
        assert window.ranges[entry] == (start, len(body))
        assert reads and min(reads) == position.absolute_byte_offset and len(reads) < 4
        job = memory_job(
            USER,
            snapshot,
            window.entry_ids,
            window.ranges,
            "run",
            1,
            output_positions=window.output_positions,
            end_output_positions=window.end_output_positions,
        )
        roots = {USER: tmp_path / "memory"}
        jobs = FileMemoryJobs(roots)
        await jobs.create(job)
        restored = await FileMemoryJobs(roots).get(USER, job.job_id)
        assert restored == job
        assert restored.output_positions[entry] == position
        evidence = await capture_memory_evidence(
            snapshot,
            entry_ids=restored.entry_ids,
            ranges=restored.ranges,
            output_reader=TrackingReader(),
            output_positions=restored.model_dump(mode="json")["output_positions"],
        )
        assert evidence.texts[0].text == body[start:]
        assert evidence.texts[0].start == start
        assert min(reads) == position.absolute_byte_offset and len(reads) < 4
        ending = restored.end_output_positions[entry]
        assert ending.absolute_byte_offset == len(body.encode())
        batches["next"] = receipt(snapshot, window)
        assert await MemorySourcePlanner(output_reader=checked).next(snapshot, batches) is None
        payload = json.loads(jobs.path(USER, job.job_id).read_text())
        payload["output_positions"][entry]["absolute_byte_offset"] += 1
        jobs.path(USER, job.job_id).write_text(json.dumps(payload))
        with pytest.raises(StorageError, match="integrity"):
            await FileMemoryJobs(roots).get(USER, job.job_id)
    finally:
        await history.aclose()


async def test_absolute_cursor_still_checks_utf8_boundary_and_full_object_integrity(tmp_path):
    history, outputs, context = await setup(tmp_path)
    try:
        result = await outputs.prepare(context, ToolResult("call-1", "Bash", "€" * 30000), "head")
        await save(history, outputs, result)
        with pytest.raises(ToolError) as error:
            await outputs.read(result.output_id, 1, 1, 4096, 1, absolute_byte_offset=1)
        assert error.value.code == "invalid_output_range"
        full = tmp_path / "sessions/s1/tool-results" / (result.output_id + ".txt")
        original = full.read_bytes()
        full.write_bytes(b"!" + original[1:])
        with pytest.raises(ToolError) as error:
            await outputs.read(result.output_id, 1, 1, 4096, 60000, absolute_byte_offset=60000)
        assert error.value.code == "output_corrupt"
    finally:
        await history.aclose()


@pytest.mark.parametrize("change", ["output_id", "character_offset"])
async def test_cursor_for_another_output_or_future_range_fails_before_read(change):
    position = MemoryOutputPosition(output_id="output_" + "0" * 32).model_dump(mode="json")
    position[change] = "output_" + "1" * 32 if change == "output_id" else 1

    class NeverRead:
        async def read(self, *args, **kwargs):
            raise AssertionError("Invalid source position reached output I/O")

    with pytest.raises(StorageError) as error:
        await output_window(NeverRead(), "output_" + "0" * 32, 0, 10, position=position)
    assert error.value.code == "memory_output_cursor_invalid"


async def test_application_commit_persists_output_position_and_reopen_uses_it(
    tmp_path, monkeypatch
):
    from tests.services.test_json_memory import service_at
    from tests.support.execution import provider
    from tests.support.http_provider import protocol_server

    from pycode.adapters.storage.layout import StorageLayout
    from pycode.app.config import AppConfig
    from pycode.app.container import build_memory_service, build_session_repository
    from pycode.core.tools import ToolContext

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")

    def unexpected_request(request):
        raise AssertionError("Empty staged changes must not invoke a model")

    with protocol_server(unexpected_request) as (url, captured):
        history, service, _ = await service_at(tmp_path, provider(url))
        reopened = None
        try:
            layout = StorageLayout(tmp_path, tmp_path / "data")
            outputs = FileToolOutputStore(layout.sessions, "s", history)
            await history.append_message(
                Message("a", "assistant", tool_calls=(ToolCall("call", "MCP", {}),))
            )
            body = "中文🙂结果\n" * 14000
            result = await outputs.prepare(
                ToolContext("s", "run", "large", str(tmp_path), "call", "MCP"),
                ToolResult("call", "MCP", body),
                "head",
            )
            entry = await history.append_message(Message("t", "tool", tool_result=result))
            await outputs.bind(result.output_id, entry)
            snapshot = await history.snapshot()
            beginning = MemoryOutputPosition(output_id=result.output_id)
            ending = advance_output(beginning, body[:101])
            job = memory_job(
                service.user_namespace,
                snapshot,
                [entry],
                {entry: (0, 101)},
                "run",
                service.timing.now(),
                output_positions={entry: beginning.model_dump(mode="json")},
                end_output_positions={entry: ending.model_dump(mode="json")},
            )
            job = await service.jobs.create(job)
            job = await service.jobs.update(job, now=service.timing.now(), status="running")
            job = await service.jobs.update(
                job, now=service.timing.now(), status="proposed", proposals={}, observed_revision=0
            )
            assert await service.process(job)
            root = service.store.roots[service.user_namespace]
            state = json.loads((root / "memory.json").read_text())
            assert state["processed_batches"][job.job_id]["end_output_positions"][entry] == (
                ending.model_dump(mode="json")
            )
            assert not state["records"]
            await service.aclose()
            await history.aclose()
            config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
            history = await build_session_repository(config).open("s")
            reopened = build_memory_service(config, history, provider(url))
            await reopened.ensure_store()
            window = await reopened.sources.next(
                await history.snapshot(), state["processed_batches"]
            )
            assert window.ranges[entry][0] == 101
            assert window.output_positions[entry] == ending.model_dump(mode="json")
            evidence = await capture_memory_evidence(
                await history.snapshot(),
                entry_ids=window.entry_ids,
                ranges=window.ranges,
                output_reader=reopened.sources.output_reader,
                output_positions=window.output_positions,
            )
            assert evidence.texts[0].text == body[101 : window.ranges[entry][1]]
            assert not captured
            # A receipt with a changed byte cursor cannot masquerade as this completed job.
            state["processed_batches"][job.job_id]["end_output_positions"][entry][
                "absolute_byte_offset"
            ] += 1
            (root / "memory.json").write_text(json.dumps(state))
            with pytest.raises(StorageError) as error:
                await reopened.process(await reopened.jobs.get(job.namespace, job.job_id))
            assert error.value.code == "memory_batch_conflict"
        finally:
            if reopened is not None:
                await reopened.aclose()
            await service.aclose()
            await history.aclose()
