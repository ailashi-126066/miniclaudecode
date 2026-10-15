"""Consumer-owned native boundaries for optional tool capabilities."""

from collections.abc import Mapping
from contextlib import AbstractAsyncContextManager
from typing import Protocol

from pycode.kernel import ExecutableTool, ToolCall, ToolContext, ToolResult


class ToolExtension(Protocol):
    def lookup(self, name: str, *, state: Mapping[str, object]) -> ExecutableTool | None: ...
    async def before_call(self, context: ToolContext, call: ToolCall) -> None: ...
    def prepare_result(self, call: ToolCall, result: ToolResult) -> ToolResult: ...
    async def after_persist(
        self, context: ToolContext, call: ToolCall, result: ToolResult, entry_id: str
    ) -> None: ...


class NativeToolBinding(Protocol):
    # The SDK object is opaque here; the framework adapter checks its native type.
    def framework_tool(self, name: str) -> object | None: ...
    def call_scope(self, name: str) -> AbstractAsyncContextManager[None]: ...
    def normalize(
        self, name: str, content: object, status: str, artifact: object, call_id: str
    ) -> ToolResult: ...
