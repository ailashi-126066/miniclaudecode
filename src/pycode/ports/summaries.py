"""Summary generation and durable publication are real replaceable I/O boundaries."""

from typing import Protocol

from pycode.core.summaries import SummaryCompletion, SummaryObject, SummarySource


class SummaryGenerator(Protocol):
    async def generate(self, source: SummarySource) -> SummaryCompletion: ...

    async def reconcile_lost(self, source: SummarySource) -> None: ...


class SummaryStore(Protocol):
    async def save_source(self, source: SummarySource) -> None: ...

    async def save_result(self, result: SummaryObject) -> None: ...

    async def load(self, job_id: str) -> tuple[SummarySource, SummaryObject | None] | None: ...

    async def invalidate(self, job_id: str, reason: str) -> None: ...
