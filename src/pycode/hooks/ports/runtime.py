"""Minimal execution capability needed by configured hook actions."""

from typing import Protocol

from pycode.kernel import ExecutableTool, StopReason, ToolCall, ToolContext, ToolResult, ToolSpec


class HookExecution(Protocol):
    async def execute(
        self, context: ToolContext, call: ToolCall, stop_reason: StopReason
    ) -> ToolResult: ...
    async def completed(self, context: ToolContext, call: ToolCall, result: ToolResult) -> None: ...


class HookPlanGuard(Protocol):
    async def guard(self, context: ToolContext, spec: ToolSpec, call: ToolCall) -> bool: ...


class HookExecutorFactory(Protocol):
    def __call__(
        self, tools: tuple[ExecutableTool, ...], *, plans: HookPlanGuard | None, read_only: bool
    ) -> HookExecution: ...
