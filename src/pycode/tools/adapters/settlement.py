"""Deep Agents boundary extracted for sequential, readable ownership."""

import asyncio
import re
from dataclasses import replace
from typing import Protocol, TypedDict, cast

from langchain.agents.middleware import (
    AgentMiddleware,
    ToolRetryMiddleware,
)
from langchain_core.messages import ToolMessage
from langgraph.errors import GraphInterrupt
from langgraph.types import Command

from pycode.kernel import (
    EventSink,
    ExecutionMode,
    Message,
    ModelSelection,
    StorageError,
    ToolCall,
    ToolContext,
    ToolError,
    ToolResult,
    ToolSpec,
    clone_value,
    data_hash,
    json_dumps,
)
from pycode.plans import PlanService
from pycode.tools import ToolExecutor, ToolOutputStore, ToolRegistry
from pycode.tools.adapters.framework_tool import make_framework_tool
from pycode.tools.adapters.state import ToolSettlementState
from pycode.tools.domain.execution import batch_execution_mode
from pycode.tools.domain.tool_failures import repeated_tool_failure, tool_failure
from pycode.tools.ports.extensions import NativeToolBinding, ToolExtension
from pycode.tools.ports.publication import MessagePublisher, ToolLifecycle


class FailureGuard(TypedDict, total=False):
    failure_tool: str
    failure_arguments: str
    failure_code: str
    failure_count: int


class SettlementMailbox(Protocol):
    async def settle_tool(self, result: ToolResult | str, *, accepted: bool) -> None: ...


class WorkspaceInvocation(Protocol):
    result: ToolResult | None


class WorkspaceTools(Protocol):
    def begin_invocation(
        self, context: ToolContext, outputs: ToolOutputStore | None
    ) -> tuple[object, WorkspaceInvocation]: ...
    def end_invocation(self, token: object) -> None: ...
    async def offload_read(
        self, context: ToolContext, arguments: dict, outputs: ToolOutputStore
    ) -> ToolResult: ...


class SettlementContext(Protocol):
    """The fields settlement actually uses, without depending on AgentRuntime state."""

    session_id: str
    run_id: str
    workspace: str
    agent_id: str
    selection: ModelSelection
    events: EventSink
    assistant: Message | None
    executor: ToolExecutor
    registry: ToolRegistry
    completed: list[Message]
    call_order: list[str]
    execution_mode: ExecutionMode
    failure_guard: FailureGuard
    serial_failure_signature: str | None
    tool_tasks: set[asyncio.Task]
    interrupted_calls: set[str]
    dispatched_calls: set[str]
    settled_calls: set[str]
    duplicate_calls: asyncio.Condition
    settlement: asyncio.Condition
    ready_results: dict[str, tuple[ToolCall, ToolResult, dict]]
    model_tools: tuple[ToolSpec, ...]
    disclosed_tool_names: tuple[str, ...] | None
    turn_end_sent: bool
    outputs: ToolOutputStore | None
    plans: PlanService | None
    team: SettlementMailbox | None
    workspace_files: WorkspaceTools | None


def retryable_read_tool_error(error):
    return isinstance(error, ToolError) and error.retryable


class ToolSettlementMiddleware(AgentMiddleware[ToolSettlementState]):
    state_schema = ToolSettlementState

    def __init__(
        self,
        *,
        publisher: MessagePublisher,
        lifecycle: ToolLifecycle[SettlementContext],
        converter,
        tool_extensions: tuple[ToolExtension, ...] = (),
        native_bindings: tuple[NativeToolBinding, ...] = (),
    ):
        self.publisher = publisher
        self.lifecycle = lifecycle
        self.converter = converter
        self.tool_extensions = tuple(tool_extensions)
        self.native_bindings = tuple(native_bindings)

    async def awrap_tool_call(self, request, handler):
        context = cast(SettlementContext, request.runtime.context)
        owner = asyncio.current_task()
        assert owner is not None
        context.tool_tasks.add(owner)
        try:
            return await self.ordered_call(request, handler)
        finally:
            context.tool_tasks.discard(owner)

    async def ordered_call(self, request, handler):
        context = cast(SettlementContext, request.runtime.context)
        specifications = []
        for call in context.assistant.tool_calls if context.assistant is not None else ():
            try:
                spec = context.registry.get_spec(call.name)
            except ToolError:
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
        context = cast(SettlementContext, request.runtime.context)
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
        framework_updates = {}
        result = await self.initial_result(context, request, call, tool_context)
        if result is None:
            request, spec, result = self.resolve_tool(request, context, call, tool_context)

            async def invoke(current_context, current_call, current_spec):
                return await self.invoke_framework(
                    request,
                    handler,
                    context,
                    call,
                    framework_updates,
                    current_context,
                    current_call,
                    current_spec,
                )

            if result is None:
                result = await self.declaration_error(context, call, spec, tool_context)

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
        if context.outputs is not None and result.output_id is None:
            # Pre-execution rejections have the same durable output contract as
            # executor results; neither the model nor the UI gets a second fact.
            result = await context.outputs.prepare(tool_context, result, "head")
            await context.events.emit(
                "tool_output_ready",
                {"call_id": result.call_id, "name": result.name, "output_id": result.output_id},
                operation_id=tool_context.operation_id,
            )
        for extension in self.tool_extensions:
            result = extension.prepare_result(call, result)
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
                await self.lifecycle(
                    context, "turn_end", context.assistant.id, message=context.assistant.text
                )
        assert result.operation_id is not None
        message = self.converter(
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

    async def initial_result(self, context, request, call, tool_context):
        result = None
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
        if result is None and repeated_tool_failure(guard, call.name, digest):
            result = ToolResult(
                call.id,
                call.name,
                "repeated_tool_failure: read the current state or choose another approach",
                is_error=True,
                error_code="repeated_tool_failure",
                details={"executed": False},
            )
        return result

    def resolve_tool(self, request, context, call, tool_context):
        result = None
        try:
            spec = context.registry.get_spec(call.name)
            request = request.override(tool=self.framework_tool(spec, context, tool_context))
        except ToolError:
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
        return request, spec, result

    async def declaration_error(self, context, call, spec, tool_context):
        result = None
        if (
            context.disclosed_tool_names is not None
            and call.name not in context.disclosed_tool_names
        ):
            # A hidden tool can never execute or request approval. Preserve a
            # more specific, purely rejecting feature policy when one applies.
            try:
                if call.parse_error:
                    raise ToolError("invalid_arguments", call.parse_error)
                context.executor.validator.validate(spec.input_schema, clone_value(call.arguments))
                for extension in self.tool_extensions:
                    await extension.validate_call(tool_context, call)
                if context.plans is not None:
                    await context.plans.guard(tool_context, spec, call)
            except ToolError as error:
                return tool_failure(error, call, executed=False)
            return ToolResult(
                call.id,
                call.name,
                "Tool was not declared for this model request",
                is_error=True,
                error_code="tool_not_declared",
                details={"executed": False},
            )
        if context.disclosed_tool_names is not None:
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
        return result

    def framework_tool(self, *args):
        return make_framework_tool(*args, native_bindings=self.native_bindings)

    def native_binding(self, name):
        for binding in self.native_bindings:
            if binding.framework_tool(name) is not None:
                return binding
        return None

    def domain_request(self, request, context, current_context, current_call, current_spec):
        current_request = request
        try:
            domain = context.registry.get(current_call.name)
        except ToolError:
            domain = None
        if domain is not None:
            current_request = request.override(
                tool_call={
                    "type": "tool_call",
                    "id": current_call.id,
                    "name": current_call.name,
                    "args": current_call.arguments,
                },
                tool=self.framework_tool(current_spec, context, current_context, domain),
            )
        return current_request

    async def call_framework_handler(
        self,
        current_request,
        handler,
        context,
        current_context,
        current_call,
        current_spec,
        native_binding,
    ):
        if native_binding is not None:
            async with native_binding.call_scope(current_call.name):
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
                return None, invocation.result
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

        return response, None

    def unwrap_tool_message(self, response, framework_updates):
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
        return response

    async def framework_result(
        self, response, context, call, current_context, current_call, native_binding
    ):
        if native_binding is not None:
            return native_binding.normalize(
                current_call.name, response.content, response.status, response.artifact, call.id
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
            code = candidate if re.fullmatch(r"[a-z_]+", candidate) else "tool_execution_error"
        return ToolResult(
            call.id,
            call.name,
            content,
            is_error=response.status == "error",
            error_code=code,
            structured_content=response.artifact,
        )

    async def invoke_framework(
        self,
        request,
        handler,
        context,
        call,
        framework_updates,
        current_context,
        current_call,
        current_spec,
    ):
        for extension in self.tool_extensions:
            await extension.before_call(current_context, current_call)
        current_request = self.domain_request(
            request, context, current_context, current_call, current_spec
        )
        native_binding = self.native_binding(current_call.name)
        response, direct = await self.call_framework_handler(
            current_request,
            handler,
            context,
            current_context,
            current_call,
            current_spec,
            native_binding,
        )
        if direct is not None:
            return direct
        response = self.unwrap_tool_message(response, framework_updates)
        if isinstance(response.artifact, ToolResult):
            return response.artifact
        return await self.framework_result(
            response, context, call, current_context, current_call, native_binding
        )

    async def settle_ready(self, context: SettlementContext):
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
            assert saved_result.operation_id is not None
            entry_id = await self.publisher.publish(
                Message("result:" + saved_result.operation_id, "tool", tool_result=saved_result),
                emit_start=True,
            )
            saved_path = saved_call.arguments.get("file_path", "")
            if (
                context.plans is not None
                and context.plans.state is not None
                and saved_path == context.plans.state.virtual_path
                and not saved_result.is_error
            ):
                await context.plans.update_exists()
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
            for extension in self.tool_extensions:
                await extension.after_persist(saved_context, saved_call, saved_result, entry_id)
            if context.team is not None:
                await context.team.settle_tool(saved_result, accepted=True)
            await context.executor.completed(saved_context, saved_call, saved_result)
            async with context.duplicate_calls:
                context.dispatched_calls.add(identifier)
                context.duplicate_calls.notify_all()
