"""Session navigation and lifetime extend the shared append-only fact journal."""

from typing import Protocol

from pycode.kernel import HistoryJournal


class SessionStore(HistoryJournal, Protocol):
    async def has_checkpoint(self, run_id: str) -> bool: ...

    async def checkpoint(self) -> object: ...

    async def branch(self, leaf_id: str | None) -> None: ...
    async def aclose(self) -> None: ...
