"""The narrow subprocess execution capability used by hooks and Python Skills."""

from collections.abc import Awaitable, Callable, Mapping
from typing import Protocol

from pycode.kernel import ToolResult
from pycode.sandbox.ports.output import StreamWriter


class ProcessRunner(Protocol):
    async def __call__(
        self,
        argv: tuple[str, ...],
        workspace: str,
        writer: StreamWriter,
        result: ToolResult,
        *,
        limit_seconds: float = 120,
        stdin: bytes | None = None,
        prefix: str = "command",
        environment: Mapping[str, str] | None = None,
        progress: Callable[[str], Awaitable[None]] | None = None,
    ) -> ToolResult: ...
