"""Await every session-owned closer before propagating the first failure or cancellation."""

import asyncio
from collections.abc import Awaitable, Callable


class SessionCleanup:
    def __init__(self) -> None:
        self._task: asyncio.Task[None] | None = None
        self._first_error: BaseException | None = None

    def record(self, error: BaseException) -> None:
        if self._first_error is None:
            self._first_error = error

    async def collect(self, closers: tuple[Callable[[], Awaitable[None]], ...]) -> None:
        for close in closers:
            try:
                await close()
            except BaseException as error:
                self.record(error)

    async def close(self, closers: tuple[Callable[[], Awaitable[None]], ...]) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self.collect(closers), name="pycode-session-cleanup")
        owned = self._task
        # Repeated cancellation of a caller never cancels the resource owner.
        # Both outer cancellation and closer failures are observed on this loop;
        # recording here preserves whichever reached the owner first.
        while not owned.done():
            try:
                await asyncio.shield(owned)
            except asyncio.CancelledError as error:
                self.record(error)
        owned.result()
        if self._first_error is not None:
            raise self._first_error
