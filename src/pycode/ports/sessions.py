"""Native session discovery and fork boundary."""

from typing import Protocol

from pycode.core.session import SessionHeader
from pycode.ports.history import SessionStore


class SessionRepository(Protocol):
    async def open(self, session_id: str, *, create: bool = False) -> SessionStore: ...

    async def list_headers(self) -> tuple[SessionHeader, ...]: ...

    async def fork(self, source_id: str, leaf_id: str | None, target_id: str) -> SessionStore: ...
