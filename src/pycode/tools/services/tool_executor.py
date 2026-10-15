# Source: Pi agent-loop.ts at 42a3497d03ad17e308a2299fa824727894f2c0ec.
# Adaptation: framework middleware owns scheduling and JSONL settlement.
"""Validate, authorize and execute one tool; no second execution ledger."""

from dataclasses import replace

from pycode.kernel import SnapshotError, StorageError, ToolError, clone_value, plain_data
from pycode.tools.domain.tool_failures import tool_failure
from pycode.tools.domain.turn_guard import tool_calls_may_execute
from pycode.tools.ports.hooks import ToolHooks
from pycode.tools.ports.tools import ToolGate


class ToolExecutor:
    def __init__(
        self,
        registry,
        validator,
        gate: ToolGate,
        events,
        outputs=None,
        *,
        error_boundary,
        snapshots=None,
        hooks: ToolHooks | None = None,
    ):
        self.error_boundary = error_boundary
        self.registry = registry
        self.validator = validator
        self.gate = gate
        self.events = events
        self.outputs = outputs
        self.snapshots = snapshots
        self.hooks = hooks

    async def execute(self, context, call, stop_reason, *, spec=None, invoke=None):
        async def progress(text):
            await self.events.emit(
                "tool_execution_update",
                {"call_id": call.id, "name": call.name, "text": text},
                operation_id=context.operation_id,
            )

        context = replace(context, call_id=call.id, tool_name=call.name, progress=progress)
        await self.events.emit(
            "tool_execution_start",
            {"call_id": call.id, "name": call.name},
            operation_id=context.operation_id,
        )
        before = None
        result_name = call.name
        result = None
        try:
            if not tool_calls_may_execute(stop_reason):
                raise ToolError(
                    "incomplete_model_output",
                    "Tool call cannot execute: model stopped at output token limit or failed",
                )
            if call.parse_error:
                raise ToolError("invalid_arguments", call.parse_error)
            if spec is None:
                spec = self.registry.get_spec(call.name)
            if invoke is None:
                tool = self.registry.get(call.name)
            arguments = clone_value(call.arguments)
            self.validator.validate(spec.input_schema, arguments)
            dispatcher = self.registry.dispatcher(call.name)
            if dispatcher is not None:
                call = dispatcher.resolve(call)
                tool = self.registry.get(call.name)
                spec = self.registry.get_spec(call.name)
                arguments = clone_value(call.arguments)
                self.validator.validate(spec.input_schema, arguments)
            hook_call = replace(call, arguments=arguments)
            if self.hooks is not None:
                rejected = await self.hooks.fire("pre_tool_use", context, hook_call, spec)
                if rejected is not None:
                    raise ToolError("hook_rejected", rejected)
                if spec.category != "read":
                    await self.hooks.fire("permission_request", context, hook_call, spec)
            await self.gate.authorize(context, spec, hook_call)
            if self.hooks is not None and spec.category == "command":
                await self.hooks.fire("command_execute", context, hook_call, spec)
            if (
                self.snapshots is not None
                and spec.source != "memory"
                and spec.category in {"write", "command", "external"}
            ):
                before = await self.snapshots.capture()
        except ToolError as error:
            # Validation, authorization and synchronous Hooks precede execution.
            result = tool_failure(error, replace(call, name=result_name))
        if result is None:

            async def action():
                if invoke is None:
                    return await tool.execute(context, arguments)
                return await invoke(context, call, spec)

            result = await self.error_boundary(replace(call, name=result_name), spec, action)
        result = replace(result, call_id=call.id, name=result_name)
        result = replace(
            result,
            session_id=context.session_id,
            run_id=context.run_id,
            operation_id=context.operation_id,
        )
        if self.outputs is not None:
            mode = "tail" if call.name.lower() in {"bash", "command"} else "head"
            result = await self.outputs.prepare(context, result, mode)
            await self.events.emit(
                "tool_output_ready",
                {
                    "call_id": result.call_id,
                    "name": result.name,
                    "output_id": result.output_id,
                },
                operation_id=context.operation_id,
            )
        if before is not None:
            assert self.snapshots is not None
            try:
                after = await self.snapshots.capture()
                paths = []
                for change in await self.snapshots.diff(before.tree_id, after.tree_id):
                    paths.append(change.path)
                result = replace(
                    result,
                    details={
                        **result.details,
                        "file_snapshot": {
                            "before": before.tree_id,
                            "after": after.tree_id,
                            "paths": paths,
                        },
                    },
                )
            except SnapshotError as error:
                raise StorageError(
                    "snapshot_settlement_failed", "Tool executed but final snapshot failed"
                ) from error
        return result

    async def completed(self, context, call, result):
        """Only notify after ToolSettlementMiddleware has persisted the result."""
        await self.events.emit(
            "tool_execution_end", plain_data(result), operation_id=context.operation_id
        )
        if self.hooks is None:
            return
        await self.hooks.fire("post_tool_use", context, call, result=result)
        changed_file = (
            not result.is_error
            and call.name in {"write_file", "edit_file"}
            and call.arguments.get("file_path", "").startswith(("/workspace/", "/plans/"))
        )
        if changed_file or result.details.get("file_snapshot", {}).get("paths"):
            await self.hooks.fire("file_change", context, call, result=result)
        if result.is_error:
            await self.hooks.fire("error", context, call, result=result)
