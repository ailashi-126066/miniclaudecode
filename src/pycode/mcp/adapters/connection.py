"""Configuration bridge to LangChain's sole MCPAdapter-owned connection."""

import asyncio
from contextlib import asynccontextmanager

from fastmcp.exceptions import MCPError
from langchain.mcp import MCPAdapter

from pycode.kernel import StorageError, ToolError


class MCPAdapterConnection:
    def __init__(self, config, environment):
        self.config = config
        self.environment = environment
        self.adapter = None
        self.tools = {}
        self.info = {}
        self.closed = False
        self.connect_lock = asyncio.Lock()

    def resolve(self, references):
        values = {}
        for key, reference in references.items():
            name = reference.removeprefix("$ENV.")
            if name not in self.environment:
                raise ToolError(
                    "mcp_environment_missing", "MCP environment reference is unavailable"
                )
            values[key] = self.environment[name]
        return values

    async def connect(self):
        async with self.connect_lock:
            await self.connect_locked()

    async def connect_locked(self):
        if self.closed:
            raise ToolError("mcp_connection_closed", "MCP adapter has been closed")
        if self.adapter is not None:
            return
        if self.config.transport == "stdio":
            server = {
                "command": self.config.command,
                "args": list(self.config.args),
                "cwd": self.config.cwd,
                "env": self.resolve(self.config.env),
            }
        else:
            server = {
                "url": self.config.url,
                "transport": "http",
                "headers": self.resolve(self.config.headers),
            }
        adapter = MCPAdapter({"mcpServers": {self.config.name: server}})
        async with asyncio.timeout(self.config.timeout):
            await adapter.__aenter__()
            try:
                declared = await adapter.list_tools()
            except BaseException:
                await adapter.__aexit__(None, None, None)
                raise
        self.adapter = adapter
        for tool in declared:
            self.tools[tool.name] = tool
        server_info = adapter.client.server_info
        capabilities = adapter.client.server_capabilities
        self.info = {
            "serverInfo": server_info.model_dump(mode="json", exclude_none=True)
            if server_info is not None
            else {},
            "capabilities": capabilities.model_dump(mode="json", exclude_none=True)
            if capabilities is not None
            else {},
            "instructions": adapter.client.instructions,
        }

    async def request(self, method, arguments):
        await self.connect()
        client = self.adapter.client
        try:
            async with self.adapter:
                async with asyncio.timeout(self.config.timeout):
                    if method == "list_tools":
                        tools = await client.list_tools(cache_mode="refresh")
                        return {
                            "tools": [
                                tool.model_dump(mode="json", by_alias=True, exclude_none=True)
                                for tool in tools
                            ]
                        }
                    if method == "list_resources":
                        resources = await client.list_resources()
                        return {
                            "resources": [
                                resource.model_dump(mode="json", by_alias=True, exclude_none=True)
                                for resource in resources
                            ]
                        }
                    if method == "list_resource_templates":
                        templates = await client.list_resource_templates()
                        return {
                            "resourceTemplates": [
                                template.model_dump(mode="json", by_alias=True, exclude_none=True)
                                for template in templates
                            ]
                        }
                    if method == "list_prompts":
                        prompts = await client.list_prompts()
                        return {
                            "prompts": [
                                prompt.model_dump(mode="json", by_alias=True, exclude_none=True)
                                for prompt in prompts
                            ]
                        }
                    if method == "read_resource":
                        contents = await client.read_resource(arguments["uri"])
                        return {
                            "contents": [
                                item.model_dump(mode="json", by_alias=True, exclude_none=True)
                                for item in contents
                            ]
                        }
                    if method == "get_prompt":
                        result = await client.get_prompt(
                            arguments["name"], arguments.get("arguments")
                        )
                        return result.model_dump(mode="json", by_alias=True, exclude_none=True)
        except (MCPError, OSError, TimeoutError) as error:
            await self.adapter.client.close()
            raise ToolError("mcp_request_failed", "MCP adapter request failed") from error
        raise ToolError("invalid_mcp_method", "Unsupported MCP operation")

    def framework_tool(self, name):
        tool = self.tools.get(name)
        if tool is None:
            raise ToolError("mcp_tool_missing", "MCP tool is no longer available")
        return tool

    async def invoke(self, name, arguments, *, call_id):
        async with self.tool_call():
            tool = self.framework_tool(name)
            return await tool.ainvoke(
                {"type": "tool_call", "id": call_id, "name": name, "args": arguments}
            )

    @asynccontextmanager
    async def tool_call(self):
        await self.connect()
        try:
            async with self.adapter:
                async with asyncio.timeout(self.config.timeout):
                    yield
        except (MCPError, OSError, TimeoutError) as error:
            await self.adapter.client.close()
            raise StorageError(
                "mcp_effects_uncertain", "MCP call interrupted; effects may have occurred"
            ) from error

    async def refresh_tools(self):
        await self.connect()
        declared = await self.adapter.list_tools(cache_mode="refresh")
        self.tools = {tool.name: tool for tool in declared}

    async def aclose(self):
        self.closed = True
        async with self.connect_lock:
            if self.adapter is not None:
                adapter, self.adapter = self.adapter, None
                await adapter.__aexit__(None, None, None)
        self.tools.clear()
