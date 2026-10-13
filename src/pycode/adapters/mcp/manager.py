# Source: MewCode local mcp/manager.py and tool_wrapper.py; fixed SHA in docs/source-map.md.
# SHA-256 manager: c43972ec32c050d47ab665e17274795d20fd382af4f439ac8db352e0aa61cd0b
# SHA-256 wrapper: aeb2cfda21b0b9c03a490762ac64ec3208ed1cea4fafb637220e820aec55840a
# Adaptation: concurrent owned connections, native catalogs, schema hashes and no blind call retry.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Manage SDK connections while the application owns catalog state and execution policy."""

import asyncio
import contextvars
from contextlib import asynccontextmanager

from pycode.adapters.mcp.connection import MCPAdapterConnection
from pycode.adapters.tools.schema import JsonSchemaValidator
from pycode.core.errors import DomainError, ToolError
from pycode.core.mcp import result_from_mcp, tool_name
from pycode.core.summary_source import data_hash
from pycode.core.values import plain_data


class MCPAdapterConnections:
    def __init__(self, servers, environment):
        self._configs = {server.name: server for server in servers if server.enabled}
        self._clients = {}
        self._environment = environment
        self._catalog = {"servers": [], "tools": []}
        self._startup = None
        self._closed = False

    def start(self):
        if self._closed:
            raise ToolError("mcp_connection_closed", "MCP connections have been closed")
        if self._startup is None:
            self._startup = asyncio.create_task(
                self.discover(), name="pycode-mcp-startup", context=contextvars.Context()
            )

    async def paged(self, client, method, field):
        # FastMCP, owned by MCPAdapter, handles all protocol pagination.
        result = await client.request(method, {})
        values = result.get(field, ())
        if not isinstance(values, list) or len(values) > 2048:
            raise ToolError("mcp_catalog_invalid", "MCP catalog exceeds limits")
        return values

    async def client(self, name):
        if self._closed or name not in self._configs:
            raise ToolError("mcp_server_unavailable", "MCP server is unavailable")
        if name not in self._clients:
            self._clients[name] = MCPAdapterConnection(self._configs[name], self._environment)
        connection = self._clients[name]
        await connection.connect()
        return connection

    async def describe(self, name):
        config = self._configs[name]
        client = await self.client(name)
        await client.refresh_tools()
        capabilities = client.info.get("capabilities", {})
        tools = await self.paged(client, "list_tools", "tools") if "tools" in capabilities else []
        resources = (
            await self.paged(client, "list_resources", "resources")
            if "resources" in capabilities
            else []
        )
        prompts = (
            await self.paged(client, "list_prompts", "prompts") if "prompts" in capabilities else []
        )
        validator = JsonSchemaValidator()
        declared, names = [], set()
        config_hash = data_hash(plain_data(config))
        for tool in sorted(tools, key=lambda tool: tool.get("name", "")):
            original = tool.get("name")
            if (
                not isinstance(original, str)
                or not original
                or len(original) > 128
                or original in names
            ):
                raise ToolError("mcp_catalog_invalid", "MCP tool names must be unique bounded text")
            names.add(original)
            schema = tool.get("inputSchema")
            if not isinstance(schema, dict):
                raise ToolError("mcp_catalog_invalid", "MCP input schema is missing")
            validator.check_schema(schema)
            annotations = tool.get("annotations", {})
            mode = config.tool_modes.get(original, config.loading)
            declared.append(
                {
                    "name": tool_name(name, original),
                    "server": name,
                    "original_name": original,
                    "description": tool.get("description") or original,
                    "input_schema": schema,
                    "schema_hash": data_hash(schema),
                    "config_hash": config_hash,
                    "annotations": annotations,
                    "trusted": config.trusted,
                    "loading": mode,
                }
            )
        information = {
            "name": name,
            "info": client.info,
            "resources": resources,
            "prompts": prompts,
            "config_hash": config_hash,
            "status": "connected",
        }
        return information, declared

    async def discover(self):
        names = sorted(self._configs)
        results = await asyncio.gather(
            *(self.describe(name) for name in names), return_exceptions=True
        )
        servers, tools = [], []
        for name, result in zip(names, results, strict=True):
            if isinstance(result, DomainError):
                servers.append({"name": name, "status": "failed", "error_code": result.code})
            elif isinstance(result, BaseException):
                raise result
            else:
                info, declared = result
                servers.append(info)
                tools.extend(declared)
        self._catalog = {"servers": servers, "tools": sorted(tools, key=lambda item: item["name"])}
        return self._catalog

    async def catalog(self):
        self.start()
        owned = self._startup
        result = await asyncio.shield(owned)
        if self._startup is owned:
            self._startup = None
        return result

    def framework_tool(self, declaration):
        connection = self._clients[declaration["server"]]
        return connection.framework_tool(declaration["original_name"]).model_copy(
            update={"name": declaration["name"]}
        )

    async def validate_call(self, declaration):
        client = await self.client(declaration["server"])
        # A reconnect may have different schemas; verify before sending effects.
        current = await self.paged(client, "list_tools", "tools")
        remote = next(
            (tool for tool in current if tool["name"] == declaration["original_name"]), None
        )
        if remote is None or data_hash(remote["inputSchema"]) != declaration["schema_hash"]:
            raise ToolError(
                "mcp_schema_changed",
                "MCP schema changed or tool disappeared; reload after settling execution",
            )
        expected = declaration.get("annotations", {})
        observed = remote.get("annotations", {})
        if any(
            expected.get(key) != observed.get(key)
            for key in (
                "readOnlyHint",
                "destructiveHint",
                "idempotentHint",
                "openWorldHint",
            )
        ):
            raise ToolError(
                "mcp_policy_changed", "MCP tool policy annotations changed before execution"
            )
        return client

    @asynccontextmanager
    async def tool_call(self, declaration):
        if declaration is None:
            raise ToolError("mcp_tool_unavailable", "MCP tool is outside the current catalog")
        client = await self.validate_call(declaration)
        async with client.tool_call():
            yield

    async def call(self, declaration, arguments, *, call_id="mcp-call"):
        """The explicit adapter entry uses the same native tool and policy boundary."""
        client = await self.validate_call(declaration)
        result = await client.invoke(declaration["original_name"], arguments, call_id=call_id)
        return result_from_mcp(
            result.content,
            result.status,
            result.artifact,
            name=declaration["name"],
            call_id=call_id,
            declaration=declaration,
        )

    async def request(self, server, method, arguments):
        client = await self.client(server)
        return await client.request(method, arguments)

    async def aclose(self):
        self._closed = True
        if self._startup is not None and not self._startup.done():
            self._startup.cancel()
        if self._startup is not None:
            await asyncio.gather(self._startup, return_exceptions=True)
        await asyncio.gather(*(client.aclose() for client in self._clients.values()))
        self._clients.clear()
