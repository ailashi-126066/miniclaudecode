"""Deep Agents boundary extracted for sequential, readable ownership."""

import asyncio
import json
import re
from dataclasses import replace

from deepagents.middleware.skills import disclosed_skill_tool_names
from langchain.agents.middleware import (
    AgentMiddleware,
    ToolRetryMiddleware,
)
from langchain_core.messages import ToolMessage
from langgraph.errors import GraphInterrupt
from langgraph.types import Command

from pycode.core.errors import StorageError, ToolError
from pycode.core.hashing import data_hash
from pycode.core.mcp import result_from_mcp
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.tools import ToolContext, ToolSpec, batch_execution_mode
from pycode.core.values import json_dumps
from pycode.workflows.boundaries import make_framework_tool, run_hook, save_message
from pycode.workflows.messages import to_framework
from pycode.workflows.state import PycodeState


def retryable_read_tool_error(error):
    return isinstance(error, ToolError) and error.retryable


class ToolSettlementMiddleware(AgentMiddleware):
    state_schema = PycodeState

    async def awrap_tool_call(self, request, handler):
        context = request.runtime.context
        owner = asyncio.current_task()
        context.tool_tasks.add(owner)
        try:
            return await self.ordered_call(request, handler)
        finally:
            context.tool_tasks.discard(owner)

    async def ordered_call(self, request, handler):
        context = request.runtime.context
        specifications = []
        for call in context.assistant.tool_calls if context.assistant is not None else ():
            try:
                spec = context.registry.get_spec(call.name)
            except ToolError:
                resource = (
                    context.skill_resources.get_tool(call.name) if context.skill_resources else None
                )
                if resource is not None:
                    spec = resource.spec
                else:
                    read_only = call.name in {
                        "read_file",
                        "ls",
                        "glob",
                        "grep",
                        "write_todos",
                        "task",
                    }
                    spec = ToolSpec(
                        call.name,
                        "Framework tool",
                        {},
                        execution_mode="parallel" if read_only else "sequential",
                    )
            specifications.append(spec)
        serial = batch_execution_mode(context.execution_mode, tuple(specifications)) == "sequential"
        if serial and request.tool_call["id"] in context.call_order:
            position = context.call_order.index(request.tool_call["id"])
            preceding = set(context.call_order[:position])
            async with context.duplicate_calls:
                await context.duplicate_calls.wait_for(
                    lambda: preceding <= context.dispatched_calls
                )
        return await self.settle_call(request, handler)

    async def settle_call(self, request, handler):
        context = request.runtime.context
        raw = request.tool_call
        call = ToolCall(raw["id"], raw["name"], raw["args"])
        if context.assistant is None or call.id not in context.call_order:
            raise StorageError(
                "model_not_persisted", "Tool call has no persisted completed model message"
            )
        for original in context.assistant.tool_calls:
            if original.id == call.id:
                call = original
                break
        tool_context = ToolContext(
            context.session_id,
            context.run_id,
            f"{context.run_id}:{context.assistant.id}:{call.id}",
            context.workspace,
            call.id,
            call.name,
            agent_id=context.agent_id,
        )
        result = None
        framework_updates = {}
        for message in context.completed:
            if (
                message.tool_result is not None
                and message.tool_result.operation_id == tool_context.operation_id
            ):
                result = message.tool_result
                break
        digest = data_hash(call.arguments)
        if context.serial_failure_signature == call.name + ":" + digest:
            async with context.duplicate_calls:
                preceding = set()
                for candidate in context.assistant.tool_calls:
                    if candidate.id == call.id:
                        break
                    if candidate.name == call.name and data_hash(candidate.arguments) == digest:
                        preceding.add(candidate.id)
                await context.duplicate_calls.wait_for(
                    lambda: preceding <= context.dispatched_calls
                )
        guard = context.failure_guard or request.state
        if (
            result is None
            and guard.get("failure_count", 0) >= 3
            and guard.get("failure_tool") == call.name
            and guard.get("failure_arguments") == digest
        ):
            result = ToolResult(
                call.id,
                call.name,
                "repeated_tool_failure: read the current state or choose another approach",
                is_error=True,
                error_code="repeated_tool_failure",
                details={"executed": False},
            )
        if (
            result is None
            and context.selected_tool_names is not None
            and (call.name not in context.selected_tool_names)
        ):
            result = ToolResult(
                call.id,
                call.name,
                "Tool was not declared for this model request",
                is_error=True,
                error_code="tool_not_declared",
                details={"executed": False},
            )
        if result is None:
            try:
                spec = context.registry.get_spec(call.name)
                request = request.override(tool=make_framework_tool(spec, context, tool_context))
            except ToolError:
                resource = (
                    context.skill_resources.get_tool(call.name)
                    if context.skill_resources is not None
                    else None
                )
                if resource is not None and call.name in disclosed_skill_tool_names(request.state):
                    spec = resource.spec
                    request = request.override(
                        tool=make_framework_tool(spec, context, tool_context, resource)
                    )
                else:
                    if request.tool is None:
                        result = ToolResult(
                            call.id,
                            call.name,
                            "Unknown tool: " + call.name,
                            is_error=True,
                            error_code="unknown_tool",
                            details={"executed": False},
                        )
                    category = (
                        "read"
                        if call.name
                        in {
                            "read_file",
                            "ls",
                            "glob",
                            "grep",
                            "write_todos",
                            "task",
                        }
                        else "write"
                    )
                    schema = request.tool.args_schema if request.tool is not None else {}
                    if not isinstance(schema, dict):
                        schema = schema.model_json_schema()
                    spec = ToolSpec(
                        call.name,
                        "Deep Agents tool",
                        schema,
                        category=category,
                        execution_mode="parallel" if category == "read" else "sequential",
                    )

            async def invoke(current_context, current_call, current_spec):
                if (
                    current_call.name == "read_file"
                    and context.agent_id == "lead"
                    and current_call.arguments.get("file_path", "").startswith("/skills/")
                    and context.skill_recorder is not None
                ):
                    selected = await context.skill_recorder.selected_entry(
                        current_call.arguments["file_path"]
                    )
                    if selected is not None and selected.mode == "fork":
                        raise ToolError(
                            "skill_requires_subagent",
                            "Use task with subagent_type=skill-" + selected.name,
                        )
                current_request = request
                try:
                    domain = context.registry.get(current_call.name)
                except ToolError:
                    domain = (
                        context.skill_resources.get_tool(current_call.name)
                        if context.skill_resources is not None
                        else None
                    )
                if domain is not None:
                    current_request = request.override(
                        tool_call={
                            "type": "tool_call",
                            "id": current_call.id,
                            "name": current_call.name,
                            "args": current_call.arguments,
                        },
                        tool=make_framework_tool(current_spec, context, current_context, domain),
                    )
                mcp_call = (
                    current_spec.source.startswith("mcp:") and current_spec.source != "mcp:catalog"
                )
                if mcp_call and context.mcp is not None and context.mcp.tool_call is not None:
                    async with context.mcp.tool_call(current_call.name):
                        response = await handler(current_request)
                elif current_call.name in {"grep", "glob"} and context.workspace_files is not None:
                    token, invocation = context.workspace_files.begin_invocation(
                        current_context, context.outputs
                    )
                    try:
                        response = await handler(current_request)
                    finally:
                        context.workspace_files.end_invocation(token)
                    if invocation.result is not None:
                        return invocation.result
                elif current_spec.category == "read":
                    retry = ToolRetryMiddleware(
                        max_retries=3,
                        retry_on=retryable_read_tool_error,
                        on_failure="error",
                        initial_delay=0,
                        jitter=False,
                    )
                    response = await retry.awrap_tool_call(current_request, handler)
                else:
                    response = await handler(current_request)
                if isinstance(response, Command):
                    if not isinstance(response.update, dict):
                        raise TypeError("Expected dictionary state updates from framework tool")
                    for key, value in response.update.items():
                        if key != "messages":
                            framework_updates[key] = value
                    tool_messages = response.update.get("messages", ())
                    if len(tool_messages) != 1:
                        raise TypeError("Framework tool must return one paired ToolMessage")
                    response = tool_messages[0]
                if not isinstance(response, ToolMessage):
                    raise TypeError("Expected a ToolMessage from framework tool execution")
                if isinstance(response.artifact, ToolResult):
                    return response.artifact
                if mcp_call:
                    return result_from_mcp(
                        response.content,
                        response.status,
                        response.artifact,
                        name=call.name,
                        call_id=call.id,
                        declaration=context.mcp.declaration(current_call.name),
                    )
                if (
                    current_call.name == "read_file"
                    and response.status == "success"
                    and isinstance(response.content, str)
                    and "Long line clipped;" in response.content
                    and current_call.arguments.get("file_path", "").startswith("/workspace/")
                    and context.workspace_files is not None
                    and context.outputs is not None
                ):
                    return await context.workspace_files.offload_read(
                        current_context, current_call.arguments, context.outputs
                    )
                content = (
                    response.content
                    if isinstance(response.content, str)
                    else json_dumps(response.content, ensure_ascii=False)
                )
                code = None
                if response.status == "error":
                    error_text = content.removeprefix("Error: ")
                    candidate = error_text.partition(":")[0]
                    code = (
                        candidate if re.fullmatch(r"[a-z_]+", candidate) else "tool_execution_error"
                    )
                return ToolResult(
                    call.id,
                    call.name,
                    content,
                    is_error=response.status == "error",
                    error_code=code,
                    structured_content=response.artifact,
                )

            if result is None and context.selected_tool_names is not None:
                for declared in context.model_tools:
                    if declared.name != call.name or declared.source == "deepagents":
                        continue
                    if (
                        data_hash(declared.input_schema) != data_hash(spec.input_schema)
                        or declared.implementation_hash != spec.implementation_hash
                        or declared.policy_hash != spec.policy_hash
                    ):
                        result = ToolResult(
                            call.id,
                            call.name,
                            "Tool declaration changed after model input",
                            is_error=True,
                            error_code="tool_declaration_changed",
                            details={"executed": False},
                        )
                    break

            if result is None:
                try:
                    result = await context.executor.execute(
                        tool_context, call, context.assistant.stop_reason, spec=spec, invoke=invoke
                    )
                except GraphInterrupt:
                    async with context.settlement:
                        context.interrupted_calls.add(call.id)
                        await self.settle_ready(context)
                        async with context.duplicate_calls:
                            context.dispatched_calls.add(call.id)
                            context.duplicate_calls.notify_all()
                        context.settlement.notify_all()
                    raise
        result = replace(
            result,
            session_id=context.session_id,
            run_id=context.run_id,
            operation_id=tool_context.operation_id,
            details={**result.details, "source_assistant_id": context.assistant.id},
        )
        skill_path = call.arguments.get("file_path", "")
        is_skill = (
            call.name == "read_file"
            and skill_path.startswith("/skills/")
            and not result.is_error
            and context.skill_recorder is not None
        )
        if is_skill:
            result = replace(result, details={**result.details, "protected_content": True})
        async with context.settlement:
            context.ready_results[call.id] = (call, result, framework_updates)
            while call.id not in context.settled_calls:
                await self.settle_ready(context)
                if call.id not in context.settled_calls:
                    await context.settlement.wait()
            context.settlement.notify_all()
            if len(context.settled_calls) == len(context.call_order) and not context.turn_end_sent:
                context.turn_end_sent = True
                await context.events.emit(
                    "turn_end", {"turn": request.state["turn"], "message_id": context.assistant.id}
                )
                await run_hook(
                    context, "turn_end", context.assistant.id, message=context.assistant.text
                )
        message = to_framework(
            Message("result:" + result.operation_id, "tool", tool_result=result),
            context.selection,
        )
        return Command(
            update={
                **framework_updates,
                "messages": [message],
                **context.failure_guard,
            }
        )

    async def settle_ready(self, context):
        for identifier in context.call_order:
            if identifier in context.settled_calls or identifier in context.interrupted_calls:
                continue
            ready = context.ready_results.get(identifier)
            if ready is None:
                break
            saved_call, saved_result, saved_updates = ready
            saved_digest = data_hash(saved_call.arguments)
            previous = context.failure_guard
            count = 0
            if saved_result.is_error:
                same = (
                    previous.get("failure_tool") == saved_call.name
                    and previous.get("failure_arguments") == saved_digest
                    and previous.get("failure_code") == saved_result.error_code
                )
                count = previous.get("failure_count", 0) + 1 if same else 1
            if saved_result.error_code == "repeated_tool_failure":
                count = previous.get("failure_count", 3)
            context.failure_guard = {
                "failure_tool": saved_call.name if saved_result.is_error else "",
                "failure_arguments": saved_digest if saved_result.is_error else "",
                "failure_code": previous.get("failure_code", "")
                if saved_result.error_code == "repeated_tool_failure"
                else saved_result.error_code or "",
                "failure_count": count,
            }
            entry_id = await save_message(
                context,
                Message("result:" + saved_result.operation_id, "tool", tool_result=saved_result),
                emit_start=True,
            )
            if (
                saved_call.name == "SkillSearch"
                and not saved_result.is_error
                and context.skill_selection is not None
                and context.skill_selection.dispatch
            ):
                selected = saved_result.structured_content
                if selected is None:
                    selected = json.loads(saved_result.text)
                paths = []
                for item in selected:
                    paths.append(item["path"])
                context.skill_selection.select(paths)
            saved_path = saved_call.arguments.get("file_path", "")
            if (
                context.plans is not None
                and context.plans.state is not None
                and saved_path == context.plans.state.virtual_path
                and not saved_result.is_error
            ):
                await context.plans.update_exists()
            if (
                saved_call.name == "read_file"
                and saved_path.startswith("/skills/")
                and not saved_result.is_error
                and context.skill_recorder is not None
            ):
                await context.skill_recorder.record(saved_path, saved_result, entry_id)
            context.settled_calls.add(identifier)
            saved_context = ToolContext(
                context.session_id,
                context.run_id,
                saved_result.operation_id,
                context.workspace,
                saved_call.id,
                saved_call.name,
                agent_id=context.agent_id,
            )
            if context.team is not None:
                await context.team.settle_tool(saved_result, accepted=True)
            await context.executor.completed(saved_context, saved_call, saved_result)
            if saved_result.details.get("critical_hook_failure"):
                raise StorageError(
                    "critical_hook_failed",
                    "A required synchronous Hook failed; this run has stopped",
                )
            async with context.duplicate_calls:
                context.dispatched_calls.add(identifier)
                context.duplicate_calls.notify_all()
