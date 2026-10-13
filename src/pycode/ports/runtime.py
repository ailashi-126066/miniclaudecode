"""A common execution boundary for main, CLI and later worker sessions."""

from typing import Protocol

from pycode.core.messages import Message
from pycode.core.models import ProviderConfig
from pycode.core.runtime import RunResult
from pycode.ports.history import SessionStore


class RuntimeLauncher(Protocol):
    async def cancel_wait(self, run_id: str): ...

    async def run(
        self,
        provider: ProviderConfig,
        store: SessionStore,
        inputs: tuple[Message, ...],
        history: tuple[Message, ...],
    ) -> RunResult: ...

    async def compact(self, provider: ProviderConfig, store: SessionStore): ...

    async def aclose(self) -> None: ...

    async def invalidate_summary(self, reason: str) -> None: ...
    async def reconcile_hook(self, store, operation_id, action, text="", is_error=False): ...

    async def resume(
        self,
        provider: ProviderConfig,
        store: SessionStore,
        run_id: str,
        answers: dict | None = None,
    ) -> RunResult: ...
