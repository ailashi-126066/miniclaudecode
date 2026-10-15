"""Single ordered event publisher; it owns no alternative message history."""

import asyncio
import logging
from typing import Any

from pycode.kernel.domain.errors import OutputClosed
from pycode.kernel.domain.events import Event, EventKind
from pycode.kernel.domain.values import clone_value
from pycode.kernel.ports.events import EventConsumer, EventJournal


class EventStream:
    def __init__(
        self,
        session_id: str,
        run_id: str,
        consumers: tuple[EventConsumer, ...],
        initial_sequence: int = 0,
        *,
        journal: EventJournal | None = None,
    ) -> None:
        self._session_id = session_id
        self._run_id = run_id
        self._consumers = consumers
        self._sequence = initial_sequence
        self._lock = asyncio.Lock()
        self._journal = journal
        self._disabled: set[int] = set()

    async def emit(
        self, kind: EventKind, data: dict[str, Any], *, operation_id: str | None = None
    ) -> Event:
        async with self._lock:
            if self._journal is not None:
                event = await self._journal.append_event(
                    self._session_id,
                    self._run_id,
                    kind,
                    clone_value(data),
                    operation_id=operation_id,
                )
                self._sequence = event.sequence
            else:
                self._sequence += 1
                event = Event(
                    sequence=self._sequence,
                    session_id=self._session_id,
                    run_id=self._run_id,
                    kind=kind,
                    data=clone_value(data),
                    operation_id=operation_id,
                )
            for index, consumer in enumerate(self._consumers):
                if index in self._disabled:
                    continue
                try:
                    await consumer.consume(clone_value(event))
                except BrokenPipeError:
                    self._disabled.add(index)
                    raise OutputClosed("Console output closed") from None
                except Exception:
                    self._disabled.add(index)
                    logging.getLogger("pycode.events").exception(
                        "Disabled failed event consumer %s for session=%s run=%s",
                        type(consumer).__name__,
                        self._session_id,
                        self._run_id,
                    )
            return event
