"""Configured actions have an explicit process/network execution boundary."""

from typing import Protocol

from pycode.core.hooks import Hook, HookContext
from pycode.core.messages import ToolResult
from pycode.core.tools import ToolContext


class HookActions(Protocol):
    async def matches(self, hook: Hook, context: HookContext) -> bool: ...
    async def execute(
        self, action: dict, context: HookContext, tool: ToolContext
    ) -> ToolResult: ...
