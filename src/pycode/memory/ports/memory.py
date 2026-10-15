"""Native background memory capabilities; model and LangGraph implementations are injected."""

from collections.abc import Sequence
from typing import Protocol

from pycode.kernel import Message
from pycode.memory.domain.memory import MemoryRecord


class MemoryUpdater(Protocol):
    async def select(
        self,
        session_id: str,
        run_id: str,
        query: str,
        records: Sequence[MemoryRecord],
        *,
        limit: int = 10,
    ) -> Sequence[tuple[str, str]] | None: ...
    async def consolidate(
        self,
        store: object,
        namespace: tuple[str, ...],
        summary: str,
        messages: Sequence[Message],
        session_id: str,
        run_id: str,
    ) -> object: ...
