"""Native executable tool capability."""

from typing import Any, Protocol

from pycode.kernel.domain.messages import ToolResult
from pycode.kernel.domain.tools import ToolContext, ToolSpec


class ExecutableTool(Protocol):
    @property
    def spec(self) -> ToolSpec: ...
    async def execute(self, context: ToolContext, arguments: dict[str, Any]) -> ToolResult: ...
