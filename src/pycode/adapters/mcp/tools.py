# Source: MewCode local mcp/tool_wrapper.py; SHA-256:
# aeb2cfda21b0b9c03a490762ac64ec3208ed1cea4fafb637220e820aec55840a
# Adaptation: native ToolResult and declarations; dispatch resolves before parent authorization.
"""MCP targets and catalog controls all execute through the common executor."""

from pycode.core.errors import ToolError
from pycode.core.mcp import mcp_spec
from pycode.core.messages import ToolCall, ToolResult
from pycode.core.tools import ToolSpec
from pycode.core.values import json_dumps


class MCPTool:
    def __init__(self, declaration, connections, *, visible=True):
        self.spec = mcp_spec(declaration, visible=visible)
        self._declaration = declaration
        self._connections = connections

    async def execute(self, context, arguments):
        return await self._connections.call(self._declaration, arguments)


class ToolSearch:
    spec = ToolSpec(
        "ToolSearch",
        "Search MCP tools and retrieve bounded concrete input schemas.",
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "maxLength": 4096},
                "limit": {"type": "integer", "minimum": 1, "maximum": 20, "default": 5},
            },
            "required": ["query"],
            "additionalProperties": False,
        },
        category="read",
        execution_mode="parallel",
        source="mcp:catalog",
    )

    def __init__(self, service):
        self._service = service

    async def execute(self, context, arguments):
        result = await self._service.search(arguments["query"], arguments.get("limit", 5))
        return ToolResult(context.call_id, self.spec.name, json_dumps(result, ensure_ascii=False))


class MCPCall:
    spec = ToolSpec(
        "MCPCall",
        "Call a tool returned by ToolSearch; target schema and permissions apply.",
        {
            "type": "object",
            "properties": {
                "server": {"type": "string"},
                "tool": {"type": "string"},
                "arguments": {"type": "object"},
            },
            "required": ["server", "tool", "arguments"],
            "additionalProperties": False,
        },
        source="mcp:catalog",
    )

    def __init__(self, service):
        self._service = service

    def resolve(self, call):
        target = self._service.target(call.arguments["server"], call.arguments["tool"])
        return ToolCall(call.id, target["name"], call.arguments["arguments"])

    async def execute(self, context, arguments):
        raise ToolError("mcp_dispatch_required", "MCPCall must resolve through the common executor")


class MCPResources:
    spec = ToolSpec(
        "MCPResources",
        "List MCP resources and resource templates without reading contents.",
        {
            "type": "object",
            "properties": {"server": {"type": "string"}},
            "required": ["server"],
            "additionalProperties": False,
        },
        category="read",
        source="mcp:catalog",
    )

    def __init__(self, service):
        self._service = service

    async def execute(self, context, arguments):
        result = await self._service.resources(arguments["server"])
        return ToolResult(context.call_id, self.spec.name, json_dumps(result, ensure_ascii=False))


class MCPReadResource:
    spec = ToolSpec(
        "MCPReadResource",
        "Read one MCP resource URI on demand.",
        {
            "type": "object",
            "properties": {"server": {"type": "string"}, "uri": {"type": "string", "minLength": 1}},
            "required": ["server", "uri"],
            "additionalProperties": False,
        },
        source="mcp:catalog",
    )

    def __init__(self, service):
        self._service = service

    async def execute(self, context, arguments):
        result = await self._service.read_resource(arguments["server"], arguments["uri"])
        return ToolResult(context.call_id, self.spec.name, json_dumps(result, ensure_ascii=False))


class MCPPrompts:
    spec = ToolSpec(
        "MCPPrompts",
        "List MCP prompts without loading prompt bodies.",
        {
            "type": "object",
            "properties": {"server": {"type": "string"}},
            "required": ["server"],
            "additionalProperties": False,
        },
        category="read",
        source="mcp:catalog",
    )

    def __init__(self, service):
        self._service = service

    async def execute(self, context, arguments):
        result = await self._service.prompts(arguments["server"])
        return ToolResult(context.call_id, self.spec.name, json_dumps(result, ensure_ascii=False))


class MCPGetPrompt:
    spec = ToolSpec(
        "MCPGetPrompt",
        "Get an MCP prompt body with named text arguments on demand.",
        {
            "type": "object",
            "properties": {
                "server": {"type": "string"},
                "name": {"type": "string"},
                "arguments": {
                    "type": "object",
                    "additionalProperties": {"type": "string"},
                    "default": {},
                },
            },
            "required": ["server", "name"],
            "additionalProperties": False,
        },
        source="mcp:catalog",
    )

    def __init__(self, service):
        self._service = service

    async def execute(self, context, arguments):
        result = await self._service.get_prompt(
            arguments["server"], arguments["name"], arguments.get("arguments", {})
        )
        return ToolResult(context.call_id, self.spec.name, json_dumps(result, ensure_ascii=False))
