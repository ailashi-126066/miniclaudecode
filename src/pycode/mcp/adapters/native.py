"""Native framework tooling remains owned by MCP behind the tools port."""

from pycode.mcp.domain.mcp import result_from_mcp


class MCPNativeBinding:
    def __init__(self, bindings):
        self.bindings = bindings

    def framework_tool(self, name):
        return self.bindings.framework_tool(name)

    def call_scope(self, name):
        return self.bindings.tool_call(name)

    def normalize(self, name, content, status, artifact, call_id):
        return result_from_mcp(
            content,
            status,
            artifact,
            name=name,
            call_id=call_id,
            declaration=self.bindings.declaration(name),
        )
