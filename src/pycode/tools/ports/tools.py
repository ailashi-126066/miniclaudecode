"""Tool execution and external input validation boundaries."""

from typing import Any, Protocol

from pycode.kernel import ToolCall, ToolContext, ToolSpec


class ArgumentValidator(Protocol):
    def check_schema(self, schema: dict[str, Any]) -> None: ...

    def validate(self, schema: dict[str, Any], arguments: dict[str, Any]) -> None: ...


class ToolGate(Protocol):
    async def authorize(self, context: ToolContext, spec: ToolSpec, call: ToolCall) -> None: ...
