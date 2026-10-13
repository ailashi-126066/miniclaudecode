# Source: Pi agent-loop.ts at 42a3497d03ad17e308a2299fa824727894f2c0ec.
# Adaptation: framework middleware owns scheduling and JSONL settlement.
"""Validate, authorize and execute one tool; no second execution ledger."""

from dataclasses import replace

from pycode.core.errors import SnapshotError, StorageError, ToolError
from pycode.core.hooks import HookContext
from pycode.core.messages import ToolResult
from pycode.core.turn_policy import tool_calls_may_execute
from pycode.core.values import clone_value, plain_data


class ToolExecutor:
    def __init__(
        self, registry, validator, gate, events, outputs=None, *, snapshots=None, hooks=None
    ):
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
        try:
            if not tool_calls_may_execute(stop_reason):
                raise ToolError(
                    "incomplete_model_output",
                    "Tool call cannot execute: model stopped at output token limit or failed",
                )
            if call.parse_error:
                raise ToolError("invalid_arguments", call.parse_error)
            if spec is None:
                tool = self.registry.get(call.name)
                spec = self.registry.get_spec(call.name)
            arguments = clone_value(call.arguments)
            self.validator.validate(spec.input_schema, arguments)
            dispatcher = self.registry.dispatcher(call.name)
            if dispatcher is not None:
                call = dispatcher.resolve(call)
                tool = self.registry.get(call.name)
                spec = self.registry.get_spec(call.name)
                arguments = clone_value(call.arguments)
                self.validator.validate(spec.input_schema, arguments)
            hook_context = HookContext(
                context.session_id,
                context.run_id,
                context.workspace,
                context.operation_id,
                "pre_tool_use",
                call.name,
                arguments,
                str(arguments.get("file_path", arguments.get("path", ""))),
            )
            if self.hooks is not None:
                rejected = await self.hooks.fire(hook_context)
                if rejected is not None:
                    raise ToolError("hook_rejected", rejected)
                if spec.category != "read":
                    await self.hooks.fire(replace(hook_context, event_name="permission_request"))
            await self.gate.authorize(context, spec, replace(call, arguments=arguments))
            if self.hooks is not None and spec.category == "command":
                await self.hooks.fire(replace(hook_context, event_name="command_execute"))
            if self.snapshots is not None and spec.category in {"write", "command", "external"}:
                before = await self.snapshots.capture()
            if invoke is None:
                executed = await tool.execute(context, arguments)
            else:
                executed = await invoke(context, call, spec)
            result = replace(executed, call_id=call.id, name=result_name)
        except (ToolError, SnapshotError) as error:
            result = ToolResult(
                call.id,
                result_name,
                str(error),
                is_error=True,
                error_code=error.code,
                details={"critical_hook_failure": True} if error.code == "hook_failed" else {},
            )
        except OSError as error:
            result = ToolResult(
                call.id, result_name, str(error), is_error=True, error_code="tool_io_error"
            )
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
        hook_context = HookContext(
            context.session_id,
            context.run_id,
            context.workspace,
            context.operation_id,
            "post_tool_use",
            call.name,
            clone_value(call.arguments),
            str(call.arguments.get("file_path", call.arguments.get("path", ""))),
            result.text,
            result.error_code or "",
        )
        await self.hooks.fire(hook_context)
        changed_file = (
            not result.is_error
            and call.name in {"write_file", "edit_file"}
            and call.arguments.get("file_path", "").startswith(("/workspace/", "/plans/"))
        )
        if changed_file or result.details.get("file_snapshot", {}).get("paths"):
            await self.hooks.fire(replace(hook_context, event_name="file_change"))
        if result.is_error:
            await self.hooks.fire(replace(hook_context, event_name="error"))
