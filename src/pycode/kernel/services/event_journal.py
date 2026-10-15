"""Bounded process-local events; restart reads JSONL completed history only."""

import asyncio
from collections import deque

from pycode.kernel.errors import StorageError
from pycode.kernel.events import Event
from pycode.kernel.values import clone_value


class MemoryEventJournal:
    def __init__(self, capacity=2048):
        if capacity < 1:
            raise ValueError("Event capacity must be positive")
        self.events = deque(maxlen=capacity)
        self.sequences: dict[tuple[str, str], int] = {}
        self.lock = asyncio.Lock()

    async def append_event(self, session_id, run_id, kind, data, *, operation_id=None):
        async with self.lock:
            key = (session_id, run_id)
            sequence = self.sequences.get(key, 0) + 1
            self.sequences[key] = sequence
            event = Event(sequence, session_id, run_id, kind, clone_value(data), operation_id)
            self.events.append(event)
            return clone_value(event)

    async def list_events(self, session_id, run_id, *, after=0, limit=1000):
        if not isinstance(after, int) or after < 0 or not 1 <= limit <= 10000:
            raise StorageError("invalid_event_cursor", "Event cursor or page size is invalid")
        async with self.lock:
            result = []
            for event in self.events:
                if (
                    event.session_id == session_id
                    and event.run_id == run_id
                    and event.sequence > after
                ):
                    result.append(clone_value(event))
                    if len(result) == limit:
                        break
            return tuple(result)

    async def aclose(self):
        self.events.clear()
        self.sequences.clear()
