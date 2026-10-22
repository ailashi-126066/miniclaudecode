"""Tool execution and external input validation boundaries."""

from typing import Protocol

from pycode.kernel import ToolCall, ToolContext, ToolSpec


class ToolGate(Protocol):
    async def authorize(self, context: ToolContext, spec: ToolSpec, call: ToolCall) -> None: ...
