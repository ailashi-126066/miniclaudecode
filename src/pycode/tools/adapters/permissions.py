"""Live framework interrupts own per-operation approval decisions."""

from dataclasses import replace

from langgraph.func import task
from langgraph.types import interrupt

from pycode.kernel import StorageError, ToolError, plain_data
from pycode.sandbox import SandboxBackend, SandboxSettings, auto_approvable, denied_command
from pycode.tools.domain.operations import operation_spec_hash


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
            # Only allow-listed read-only commands skip approval inside the sandbox.
            if (
                status is not None
                and status.auto_approve
                and auto_approvable(call.arguments.get("command", ""))
            ):
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


class TeamToolGate:
    """Every merge uses its own source/target-bound approval outside Auto Plan."""

    def __init__(self, team, parent, constraints, plans=None):
        self.team, self.parent, self.constraints, self.plans = team, parent, constraints, plans
        self.mandatory = InterruptToolGate()

    async def authorize(self, context, spec, call):
        if spec.name != "merge_agent" or spec.source != "team":
            await self.parent.authorize(context, spec, call)
            return
        await self.constraints.authorize(context, spec, call)
        if self.plans is not None:
            await self.plans.guard(context, spec, call)
        action = call.arguments.get("action", "merge")
        preview = await self.team.merge_preview(call.arguments["id"], action)
        previous = self.team.previews.get(context.operation_id)
        if previous is not None and previous != preview:
            self.team.previews.pop(context.operation_id, None)
            raise ToolError(
                "merge_preview_stale", "Source or target changed; review and request approval again"
            )
        self.team.previews[context.operation_id] = preview
        await self.mandatory.authorize(
            context, replace(spec, approval_details={"merge": preview}), call
        )
