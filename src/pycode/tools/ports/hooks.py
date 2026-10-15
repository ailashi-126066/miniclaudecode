"""Tool lifecycle notifications without hook-engine implementation dependencies."""

from typing import Protocol

from pycode.kernel import ToolCall, ToolContext, ToolResult, ToolSpec


class ToolHooks(Protocol):
    async def fire(
        self,
        event_name: str,
        context: ToolContext,
        call: ToolCall,
        spec: ToolSpec | None = None,
        result: ToolResult | None = None,
    ) -> str | None: ...
