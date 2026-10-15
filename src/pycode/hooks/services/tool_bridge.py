"""Translate neutral tool events to the hook feature's context vocabulary."""

from pycode.hooks.domain.hooks import HookContext
from pycode.hooks.services.hooks import HookEngine
from pycode.kernel import ToolCall, ToolContext, ToolResult, ToolSpec, clone_value


class ToolHookBridge:
    def __init__(self, engine: HookEngine):
        self.engine = engine

    async def fire(
        self,
        event_name: str,
        context: ToolContext,
        call: ToolCall,
        spec: ToolSpec | None = None,
        result: ToolResult | None = None,
    ) -> str | None:
        return await self.engine.fire(
            HookContext(
                context.session_id,
                context.run_id,
                context.workspace,
                context.operation_id,
                event_name,
                call.name,
                clone_value(call.arguments),
                str(call.arguments.get("file_path", call.arguments.get("path", ""))),
                result.text if result is not None else "",
                result.error_code or "" if result is not None else "",
            )
        )
