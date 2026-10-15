"""Branch catalogs describe tools; SDK clients never own conversation state."""

import asyncio

from pycode.core.errors import ToolError
from pycode.core.hashing import data_hash
from pycode.core.mcp import active_mcp, catalog_hash, choose_tools
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.values import clone_value
from pycode.ports.mcp import MCPConnections


class MCPService:
    def __init__(self, history, connections: MCPConnections):
        self._history = history
        self._connections = connections
        self._lock = asyncio.Lock()
        self._catalog = {"servers": [], "tools": []}

    async def initialize(self, *, pending=False):
        remote = await self._connections.catalog()
        async with self._lock:
            snapshot = await self._history.snapshot()
            path = SessionTree(snapshot.index).path(snapshot.leaf_id)
            previous = active_mcp(path)
            digest = catalog_hash(remote)
            if previous is not None and previous["hash"] != digest:
                changed = {"source": "mcp", "previous": previous["hash"], "current": digest}
                if not any(
                    entry.type == "tool_set_change" and entry.data == changed for entry in path
                ):
                    await self._history.append_entry(EntryDraft("tool_set_change", data=changed))
            if previous is None or (not pending and previous["hash"] != digest):
                text = "MCP servers (resources and prompts load on demand):\n" + "\n".join(
                    "- " + server["name"] + ": " + server["status"] for server in remote["servers"]
                )
                saved = await self._history.append_entry(
                    EntryDraft("mcp_catalog", data={**remote, "hash": digest, "text": text})
                )
                previous = saved.data
            self._catalog = clone_value(previous)

    async def refresh(self, registry, factory, controls, input_limit):
        snapshot = await self._history.snapshot()
        catalog = active_mcp(SessionTree(snapshot.index).path(snapshot.leaf_id))
        if catalog is None:
            await self.initialize()
            catalog = self._catalog
        self._catalog = clone_value(catalog)
        specs = choose_tools(catalog, input_limit)
        tools = tuple(
            factory(tool, spec.model_visible)
            for tool, spec in (
                (self.target(spec.source[len("mcp:") :], spec.name), spec) for spec in specs
            )
        )
        registry.replace_source("mcp:", (*controls, *tools))

    def target(self, server, name):
        for tool in self._catalog["tools"]:
            if tool["server"] == server and name in {tool["name"], tool["original_name"]}:
                if tool.get("loading") == "off":
                    break
                return clone_value(tool)
        raise ToolError(
            "mcp_tool_unavailable", "MCP tool is not enabled in the current branch catalog"
        )

    async def search(self, query, limit):
        terms = query.casefold().split()
        found = []
        for tool in self._catalog["tools"]:
            if tool.get("loading") == "off":
                continue
            text = (tool["name"] + " " + tool["description"]).casefold()
            score = sum(term in text for term in terms)
            if not terms or score:
                found.append((score, tool))
        found.sort(key=lambda item: (-item[0], item[1]["name"]))
        result = [tool for _, tool in found[:limit]]
        if result:
            names = [tool["name"] for tool in result]
            snapshot = await self._history.snapshot()
            path = SessionTree(snapshot.index).path(snapshot.leaf_id)
            if not any(
                entry.type == "tool_discovery" and entry.data.get("names") == names
                for entry in path
            ):
                await self._history.append_entry(
                    EntryDraft(
                        "tool_discovery",
                        data={"source": "mcp", "names": names, "hash": data_hash(result)},
                    )
                )
        return clone_value(result)

    def declaration(self, name):
        for tool in self._catalog["tools"]:
            if tool["name"] == name and tool.get("loading") != "off":
                return clone_value(tool)
        return None

    async def resources(self, server):
        resources = await self._connections.request(server, "list_resources", {})
        templates = await self._connections.request(server, "list_resource_templates", {})
        return {"resources": resources, "templates": templates}

    async def read_resource(self, server, uri):
        return await self._connections.request(server, "read_resource", {"uri": uri})

    async def prompts(self, server):
        return await self._connections.request(server, "list_prompts", {})

    async def get_prompt(self, server, name, arguments):
        return await self._connections.request(
            server, "get_prompt", {"name": name, "arguments": arguments}
        )
