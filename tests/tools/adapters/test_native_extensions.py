"""Generic native settlement preserves dispatch identity without feature knowledge."""

from types import SimpleNamespace

from pycode.kernel import ToolCall, ToolResult
from pycode.tools.adapters.settlement import ToolSettlementMiddleware


async def test_dispatch_normalizes_target_declaration_before_executor_restores_public_name():
    seen = []

    class Binding:
        def normalize(self, name, content, status, artifact, call_id):
            seen.append((name, call_id))
            return ToolResult(call_id, name, "done", details={"remote": name})

    middleware = ToolSettlementMiddleware(publisher=None, lifecycle=None, converter=None)
    public = ToolCall("call", "MCPCall", {})
    target = ToolCall("call", "mcp__server__tool", {})
    result = await middleware.framework_result(
        SimpleNamespace(content="done", status="success", artifact=None),
        None,
        public,
        None,
        target,
        Binding(),
    )
    assert seen == [("mcp__server__tool", "call")]
    assert result.details["remote"] == "mcp__server__tool"
