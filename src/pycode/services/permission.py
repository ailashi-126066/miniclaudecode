"""Default policy; write and external actions require a real approval boundary."""

from pycode.core.errors import ToolError
from pycode.core.messages import ToolCall
from pycode.core.tools import ToolContext, ToolSpec


class ReadOnlyToolGate:
    async def authorize(self, context: ToolContext, spec: ToolSpec, call: ToolCall) -> None:
        if spec.category != "read":
            raise ToolError("approval_required", f"Approval required for {call.name}")


class IsolatedWorkerToolGate:
    """Delegation approval grants project effects only inside the owned worktree."""

    def __init__(self, external_gate):
        self.external_gate = external_gate

    async def authorize(self, context, spec, call):
        if spec.category == "read" or spec.name in {"write_file", "edit_file"}:
            return
        await self.external_gate.authorize(context, spec, call)
