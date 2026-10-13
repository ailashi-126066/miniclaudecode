import asyncio

import pytest

from pycode.services.events import EventStream


class Recorder:
    def __init__(self):
        self.events = []

    async def consume(self, event):
        self.events.append(event)


async def test_concurrent_producers_have_one_ordered_event_sequence():
    recorder = Recorder()
    stream = EventStream("s1", "r1", (recorder,), initial_sequence=10)
    await asyncio.gather(*(stream.emit("message_delta", {"text": str(i)}) for i in range(20)))
    assert [event.sequence for event in recorder.events] == list(range(11, 31))
    assert all(event.session_id == "s1" and event.run_id == "r1" for event in recorder.events)


async def test_consumer_and_producer_cannot_mutate_another_consumers_payload():
    class MutatingConsumer:
        async def consume(self, event):
            event.data["nested"]["text"] = "changed"

    recorder = Recorder()
    stream = EventStream("s1", "r1", (MutatingConsumer(), recorder))
    data = {"nested": {"text": "original"}}
    published = await stream.emit("message_end", data)
    data["nested"]["text"] = "later"
    assert recorder.events[0].data["nested"]["text"] == "original"
    assert published.data["nested"]["text"] == "original"


async def test_failed_consumer_is_disabled_without_interrupting_delivery(caplog):
    class FailingConsumer:
        calls = 0

        async def consume(self, event):
            self.calls += 1
            raise ValueError("broken display")

    failing = FailingConsumer()
    recorder = Recorder()
    stream = EventStream("s1", "r1", (failing, recorder))
    await stream.emit("tool_execution_start", {"call_id": "c1"})
    await stream.emit("tool_execution_end", {"call_id": "c1", "text": "persisted"})
    assert failing.calls == 1
    assert [event.sequence for event in recorder.events] == [1, 2]
    assert "Disabled failed event consumer" in caplog.text


async def test_broken_pipe_requests_controlled_cancellation():
    class ClosedConsole:
        async def consume(self, event):
            raise BrokenPipeError("closed")

    stream = EventStream("s1", "r1", (ClosedConsole(),))
    with pytest.raises(asyncio.CancelledError, match="Console output closed"):
        await stream.emit("message_delta", {"text": "cancel"})
