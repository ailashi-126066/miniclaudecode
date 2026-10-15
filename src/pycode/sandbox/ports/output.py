"""Only streaming publication is required by command tools."""

from typing import Protocol

from pycode.kernel import PreviewMode, ToolContext, ToolResult


class StreamWriter(Protocol):
    async def append(self, data: bytes) -> None: ...
    async def finish(self, result: ToolResult) -> ToolResult: ...
    async def abort(self) -> None: ...


class OutputSink(Protocol):
    async def begin(self, context: ToolContext, mode: PreviewMode) -> StreamWriter: ...
