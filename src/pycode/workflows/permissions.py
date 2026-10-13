"""Live framework interrupts own per-operation approval decisions."""

from langgraph.func import task
from langgraph.types import interrupt

from pycode.core.command_policy import denied_command
from pycode.core.errors import StorageError, ToolError
from pycode.core.operations import operation_spec_hash
from pycode.core.sandbox import SandboxSettings
from pycode.core.values import plain_data
from pycode.ports.sandbox import SandboxBackend


@task(name="pycode_permission")
async def permission_answer(question):
    return interrupt(question)


class InterruptToolGate:
    def __init__(
        self, sandbox: SandboxBackend | None = None, settings: SandboxSettings | None = None
    ):
        self._sandbox = sandbox
        self._settings = settings or SandboxSettings()

    async def authorize(self, context, spec, call) -> None:
        if spec.category == "read":
            return
        status = None
        if spec.name == "Bash" and spec.source == "builtin":
            reason = denied_command(call.arguments.get("command", ""))
            if reason is not None:
                raise ToolError("command_denied", reason)
            if self._sandbox is not None:
                status = await self._sandbox.status(self._settings)
        elif spec.source.startswith("skill:") and self._sandbox is not None:
            status = await self._sandbox.status(self._settings)
        if (
            spec.name == "Bash"
            and spec.source == "builtin"
            and self._settings.mode == "on-auto"
            and self._sandbox is not None
        ):
            if status.auto_approve:
                return
        question = {
            "kind": "permission",
            "operation_id": context.operation_id,
            "session_id": context.session_id,
            "run_id": context.run_id,
            "workspace": context.workspace,
            "call": plain_data(call),
            "spec_hash": operation_spec_hash(spec),
            "sandbox": plain_data(status) if status is not None else None,
            "approval_details": spec.approval_details,
        }
        answer = await permission_answer(question)
        if not isinstance(answer, dict) or answer.get("operation_id") != context.operation_id:
            raise StorageError("invalid_approval", "Approval must identify the pending operation")
        if answer.get("decision") == "deny":
            raise ToolError("permission_denied", "User denied this tool operation")
        if answer.get("decision") != "approve":
            raise StorageError("invalid_approval", "Approval decision must be approve or deny")
