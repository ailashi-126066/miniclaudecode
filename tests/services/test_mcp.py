import pytest
from tests.services.test_model_control import store_for

from pycode.adapters.mcp.tools import MCPCall, MCPTool, ToolSearch
from pycode.adapters.tools.schema import JsonSchemaValidator
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.tools import ToolContext
from pycode.services.events import EventStream
from pycode.services.mcp import MCPService
from pycode.services.permission import ReadOnlyToolGate
from pycode.services.tool_executor import ToolExecutor
from pycode.services.tool_registry import ToolRegistry
from pycode.workflows.tool_errors import execute_tool_errors


class Connections:
    def __init__(self, *, trusted):
        self.calls = []
        self.tools = [
            {
                "name": "mcp__srv__search",
                "server": "srv",
                "original_name": "search",
                "description": "search",
                "input_schema": {
                    "type": "object",
                    "properties": {"count": {"type": "integer"}},
                    "required": ["count"],
                },
                "schema_hash": "schema",
                "config_hash": "config",
                "annotations": {"readOnlyHint": True},
                "trusted": trusted,
                "loading": "dispatch",
            }
        ]

    async def catalog(self):
        return {"servers": [{"name": "srv", "status": "connected"}], "tools": self.tools}

    async def call(self, tool, arguments):
        self.calls.append((tool, arguments))
        return ToolResult("", tool["name"], "MCP done")


@pytest.mark.parametrize(
    "trusted,argument,expected",
    [(True, 2, None), (False, 2, "approval_required"), (True, "2", "invalid_arguments")],
)
async def test_dispatch_checks_target_schema_and_parent_permission_before_call(
    tmp_path, trusted, argument, expected
):
    history = store_for(tmp_path)
    await history.append_message(Message("u1", "user", "task"))
    connections = Connections(trusted=trusted)
    service = MCPService(history, connections)
    try:
        await service.initialize()
        validator = JsonSchemaValidator()
        registry = ToolRegistry(validator)
        dispatch = MCPCall(service)
        await service.refresh(
            registry,
            lambda tool, visible: MCPTool(tool, connections, visible=visible),
            (dispatch, ToolSearch(service)),
            1000,
        )
        registry.register_dispatch("MCPCall", dispatch)
        assert "mcp__srv__search" not in {spec.name for spec in registry.model_specs()}
        context = ToolContext("s1", "r1", "op", str(tmp_path))
        result = await ToolExecutor(
            registry,
            validator,
            ReadOnlyToolGate(),
            EventStream("s1", "r1", ()),
            error_boundary=execute_tool_errors,
        ).execute(
            context,
            ToolCall(
                "call",
                "MCPCall",
                {"server": "srv", "tool": "search", "arguments": {"count": argument}},
            ),
            "tool_calls",
        )
        assert result.name == "MCPCall" and result.call_id == "call"
        assert result.error_code == expected
        assert len(connections.calls) == (1 if expected is None else 0)
    finally:
        await history.aclose()
