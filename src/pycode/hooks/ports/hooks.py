"""Configured actions have an explicit process/network execution boundary."""

from typing import Protocol

from pycode.hooks.domain.hooks import Hook, HookContext
from pycode.kernel import ToolContext, ToolResult


class HookActions(Protocol):
    async def matches(self, hook: Hook, context: HookContext) -> bool: ...
    async def execute(
        self, action: dict, context: HookContext, tool: ToolContext
    ) -> ToolResult: ...
