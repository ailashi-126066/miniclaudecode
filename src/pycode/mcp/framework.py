"""One per-run binding connects native MCP tools to the common execution boundary."""

from pycode.mcp.tools import MCPTool
from pycode.mcp.types import result_from_mcp


class MCPRun:
    def __init__(self, service, connections, *, pending=False):
        self._service = service
        self._connections = connections
        self._pending = pending
        self._initialized = False

    def framework_tool(self, name):
        declaration = self._service.declaration(name)
        return self._connections.framework_tool(declaration) if declaration is not None else None

    def call_scope(self, name):
        return self._connections.tool_call(self._service.declaration(name))

    def normalize(self, name, content, status, artifact, call_id):
        return result_from_mcp(
            content,
            status,
            artifact,
            name=name,
            call_id=call_id,
            declaration=self._service.declaration(name),
        )

    def _tool(self, declaration):
        return MCPTool(declaration, self._connections)

    async def refresh(self, registry):
        if not self._initialized:
            await self._service.initialize(pending=self._pending)
            self._initialized = True
        await self._service.refresh(registry, self._tool)
