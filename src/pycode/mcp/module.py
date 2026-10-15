"""MCP-owned assembly, configuration validation and connection lifetime."""

import asyncio
from dataclasses import dataclass
from pathlib import Path

from pycode.kernel import SessionEntry, ToolSpec, data_hash, plain_data
from pycode.mcp.adapters.config import load_servers
from pycode.mcp.adapters.manager import MCPAdapterConnections
from pycode.mcp.adapters.native import MCPNativeBinding
from pycode.mcp.adapters.tools import (
    MCPCall,
    MCPGetPrompt,
    MCPPrompts,
    MCPReadResource,
    MCPResources,
    MCPTool,
    ToolSearch,
)
from pycode.mcp.domain.mcp import choose_tools
from pycode.mcp.services.answers import MCPQuestionExtension
from pycode.mcp.services.configuration import validate_mcp_recovery
from pycode.mcp.services.mcp import MCPService
from pycode.mcp.services.mcp_bindings import MCPRuntimeBindings
from pycode.mcp.services.projection import MCPProjection as MCPProjection


@dataclass(frozen=True)
class MCPSettings:
    workspace: Path
    user_data: Path
    project_trusted: bool = False


@dataclass(frozen=True)
class MCPRun:
    bindings: MCPRuntimeBindings
    tools: tuple
    native_binding: MCPNativeBinding
    question_extension: MCPQuestionExtension

    async def refresh(self, registry):
        await self.bindings.refresh(registry)


class MCPSession:
    def __init__(self, config_hash, connections):
        self.config_hash, self.connections = config_hash, connections

    def prepare_run(self, history, input_limit: int, pending=False) -> MCPRun | None:
        connections = self.connections
        if connections is None:
            return None
        service = MCPService(history, connections)
        controls = tuple(
            tool(service)
            for tool in (
                ToolSearch,
                MCPCall,
                MCPResources,
                MCPReadResource,
                MCPPrompts,
                MCPGetPrompt,
            )
        )

        def factory(declaration, visible):
            return MCPTool(declaration, connections, visible=visible)

        bindings = MCPRuntimeBindings(
            service,
            factory,
            controls,
            input_limit,
            pending=pending,
            framework_tools=connections.framework_tool,
            tool_boundary=connections.tool_call,
        )
        return MCPRun(bindings, controls, MCPNativeBinding(bindings), MCPQuestionExtension())

    async def aclose(self):
        if self.connections is not None:
            await self.connections.aclose()


class MCPModule:
    def __init__(self, *, schema):
        self.schema = schema

    async def open_session(
        self,
        settings: MCPSettings,
        credentials,
        *,
        pending: SessionEntry | None = None,
        connect: bool = True,
    ) -> MCPSession:
        roots = [settings.user_data / "mcp.json"]
        if settings.project_trusted:
            roots.append(settings.workspace / ".pycode" / "mcp.json")
        owned = asyncio.create_task(
            asyncio.to_thread(load_servers, tuple(roots), settings.workspace)
        )
        try:
            servers = await asyncio.shield(owned)
        except asyncio.CancelledError:
            await owned
            raise
        digest = data_hash([plain_data(server) for server in servers]) if servers else ""
        validate_mcp_recovery(pending, digest)
        connections = (
            MCPAdapterConnections(servers, credentials, validator=self.schema)
            if connect and any(server.enabled for server in servers)
            else None
        )
        try:
            if connections is not None:
                connections.start()
            return MCPSession(digest, connections)
        except BaseException:
            if connections is not None:
                await connections.aclose()
            raise

    @staticmethod
    def view_specs(projection, selection, *, input_limit: int) -> tuple[ToolSpec, ...]:
        catalog = projection.checkpoint.get("mcp")
        if catalog is None or not selection.capabilities.supports_tools:
            return ()
        controls = tuple(
            tool.spec
            for tool in (
                ToolSearch,
                MCPCall,
                MCPResources,
                MCPReadResource,
                MCPPrompts,
                MCPGetPrompt,
            )
        )
        declared = choose_tools(catalog, input_limit)
        return (*controls, *(spec for spec in declared if spec.model_visible))
