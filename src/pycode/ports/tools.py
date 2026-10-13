"""Tool execution and external input validation boundaries."""

from typing import Any, Protocol

from pycode.core.messages import ToolCall, ToolResult
from pycode.core.tools import ToolContext, ToolSpec


class Tool(Protocol):
    @property
    def spec(self) -> ToolSpec: ...

    async def execute(self, context: ToolContext, arguments: dict[str, Any]) -> ToolResult: ...


class ArgumentValidator(Protocol):
    def check_schema(self, schema: dict[str, Any]) -> None: ...

    def validate(self, schema: dict[str, Any], arguments: dict[str, Any]) -> None: ...


class ToolGate(Protocol):
    async def authorize(self, context: ToolContext, spec: ToolSpec, call: ToolCall) -> None: ...
