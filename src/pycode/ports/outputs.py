"""Complete output persistence and scoped readback boundaries."""

from typing import Protocol

from pycode.core.messages import ToolResult
from pycode.core.outputs import OutputSlice
from pycode.core.tools import ToolContext
from pycode.core.truncation import PreviewMode


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
        self, output_id: str, offset: int, limit: int, max_bytes: int, byte_offset: int = 0
    ) -> OutputSlice: ...
