"""Actual MCPAdapter stdio transport, LangChain tools and resource wrappers."""

import sys
from pathlib import Path

from pycode.mcp.adapters.connection import MCPAdapterConnection
from pycode.mcp.domain.mcp import MCPServer


async def test_adapter_stdio_tools_resources_prompts_and_close(tmp_path):
    script = Path(__file__).parents[2] / "support" / "mcp_server.py"
    config = MCPServer(
        "fixture",
        "stdio",
        command=sys.executable,
        args=("-X", "utf8", str(script)),
        cwd=str(tmp_path),
        trusted=True,
    )
    connection = MCPAdapterConnection(config, {})
    try:
        await connection.connect()
        tools = await connection.request("list_tools", {})
        assert "echo" in [tool["name"] for tool in tools["tools"]]
        async with connection.tool_call():
            result = await connection.framework_tool("echo").ainvoke(
                {
                    "type": "tool_call",
                    "id": "adapter-call",
                    "name": "echo",
                    "args": {"value": "real adapter"},
                }
            )
        assert result.status == "success"
        assert result.content[0]["text"] == "echo:real adapter"
        assert result.tool_call_id == "adapter-call"
        resources = await connection.request("list_resources", {})
        assert resources["resources"][0]["uri"] == "fixture://note"
        content = await connection.request("read_resource", {"uri": "fixture://note"})
        assert content["contents"][0]["text"] == "Resource content stays on demand"
        prompts = await connection.request("list_prompts", {})
        assert prompts["prompts"][0]["name"] == "explain"
        prompt = await connection.request(
            "get_prompt", {"name": "explain", "arguments": {"topic": "agent"}}
        )
        assert prompt["messages"][0]["content"]["text"] == "Explain agent"
    finally:
        await connection.aclose()
    assert connection.closed
    assert connection.adapter is None
