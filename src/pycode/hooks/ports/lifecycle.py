"""Read-only lifecycle capabilities consumed by the hook bridge."""

from typing import Protocol

from pycode.hooks.domain.hooks import HookContext


class LifecycleHooks(Protocol):
    async def fire(self, context: HookContext) -> str | None: ...


class LifecycleContext(Protocol):
    @property
    def session_id(self) -> str: ...
    @property
    def run_id(self) -> str: ...
    @property
    def workspace(self) -> str: ...
    @property
    def hooks(self) -> LifecycleHooks | None: ...
