"""Clock and cancellable delay boundary."""

from typing import Protocol


class Timing(Protocol):
    def now(self) -> float: ...

    async def sleep(self, seconds: float) -> None: ...
