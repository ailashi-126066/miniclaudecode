"""Complete output persistence and scoped readback boundaries."""

from typing import Protocol

from pycode.kernel import PreviewMode, SessionHeader, ToolContext, ToolResult
from pycode.tools.domain.outputs import OutputSlice


class OutputWriter(Protocol):
    async def append(self, data: bytes) -> None: ...

    async def finish(self, result: ToolResult) -> ToolResult: ...

    async def abort(self) -> None: ...


class ToolOutputStore(Protocol):
    async def interrupted_reference(self, context: ToolContext) -> str | None: ...

    async def prepare(
        self, context: ToolContext, result: ToolResult, mode: PreviewMode
    ) -> ToolResult: ...

    async def bind(self, output_id: str, entry_id: str) -> None: ...

    async def begin(self, context: ToolContext, mode: PreviewMode) -> OutputWriter: ...

    async def read(
        self,
        output_id: str,
        offset: int,
        limit: int,
        max_bytes: int,
        byte_offset: int = 0,
        *,
        absolute_byte_offset: int | None = None,
    ) -> OutputSlice: ...


class SessionHeaderReader(Protocol):
    def read(self, session_id: str) -> SessionHeader: ...
