"""Events are process-local; completed facts belong to JSONL."""

import asyncio

from pycode.services.event_journal import MemoryEventJournal
from pycode.services.events import EventStream


async def test_shared_publishers_are_ordered_but_restart_starts_empty(tmp_path):
    journal = MemoryEventJournal()
    first = EventStream("s", "r", (), journal=journal)
    second = EventStream("s", "r", (), journal=journal)
    await asyncio.gather(
        *(
            first.emit("notification", {"i": i}) if i % 2 else second.emit("notification", {"i": i})
            for i in range(20)
        )
    )
    assert [event.sequence for event in await journal.list_events("s", "r")] == list(range(1, 21))
    restarted = MemoryEventJournal()
    assert await restarted.list_events("s", "r") == ()
    event = await EventStream("s", "r", (), journal=restarted).emit(
        "result", {"outcome": "completed"}
    )
    assert event.sequence == 1
    assert list(tmp_path.iterdir()) == []


async def test_bounded_events_survive_consumer_error_only_within_current_process():
    class Disconnected:
        async def consume(self, event):
            raise ConnectionError("terminal disconnected")

    journal = MemoryEventJournal(capacity=2)
    stream = EventStream("s", "r", (Disconnected(),), journal=journal)
    await stream.emit("message_delta", {"text": "你好"})
    await stream.emit("message_delta", {"text": "二"})
    await stream.emit("message_end", {"text": "完成"})
    events = await journal.list_events("s", "r")
    assert [event.sequence for event in events] == [2, 3]
    assert events[-1].data == {"text": "完成"}
    await journal.aclose()
    assert await journal.list_events("s", "r") == ()
