"""Mcp tools: component-owned implementation."""

# Source: MewCode local mcp/tool_wrapper.py; SHA-256:
# aeb2cfda21b0b9c03a490762ac64ec3208ed1cea4fafb637220e820aec55840a
# Adaptation: concrete tools use common validation and authorization.

from pycode.mcp.types import mcp_spec


class MCPTool:
    def __init__(self, declaration, connections, *, visible=True):
        self.spec = mcp_spec(declaration, visible=visible)
        self._declaration = declaration
        self._connections = connections

    async def execute(self, context, arguments):
        return await self._connections.call(self._declaration, arguments)
