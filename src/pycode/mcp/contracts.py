"""Mcp contracts: component-owned implementation."""

from typing import Protocol

from pycode.kernel import ToolResult


class MCPConnections(Protocol):
    async def catalog(self) -> dict: ...
    async def call(self, declaration: dict, arguments: dict) -> ToolResult: ...
    async def aclose(self) -> None: ...
