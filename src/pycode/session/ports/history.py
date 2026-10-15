"""Session navigation and lifetime extend the shared append-only fact journal."""

from typing import Protocol

from pycode.kernel import HistoryJournal


class SessionStore(HistoryJournal, Protocol):
    async def branch(self, leaf_id: str | None) -> None: ...
    async def aclose(self) -> None: ...
