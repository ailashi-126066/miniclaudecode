"""Mcp api: component-owned implementation."""

import asyncio

from pycode.kernel import EntryDraft, SessionTree, clone_value
from pycode.mcp.contracts import MCPConnections
from pycode.mcp.types import active_mcp, catalog_hash


class MCP:
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
                text = "MCP servers (tools):\n" + "\n".join(
                    "- " + server["name"] + ": " + server["status"] for server in remote["servers"]
                )
                saved = await self._history.append_entry(
                    EntryDraft("mcp_catalog", data={**remote, "hash": digest, "text": text})
                )
                previous = saved.data
            self._catalog = clone_value(previous)

    async def refresh(self, registry, factory):
        snapshot = await self._history.snapshot()
        catalog = active_mcp(SessionTree(snapshot.index).path(snapshot.leaf_id))
        if catalog is None:
            await self.initialize()
            catalog = self._catalog
        self._catalog = clone_value(catalog)
        tools = tuple(factory(tool) for tool in self._catalog["tools"] if tool["enabled"])
        registry.replace_source("mcp:", tools)

    def declaration(self, name):
        for tool in self._catalog["tools"]:
            if tool["name"] == name and tool["enabled"]:
                return clone_value(tool)
        return None
