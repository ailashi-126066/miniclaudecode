"""System wall clock and owned asynchronous sleeps."""

import asyncio
import time


class SystemTiming:
    def now(self) -> float:
        return time.time()

    async def sleep(self, seconds: float) -> None:
        await asyncio.sleep(seconds)
