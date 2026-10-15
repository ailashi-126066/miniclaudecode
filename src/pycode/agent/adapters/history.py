"""Deep Agents boundary extracted for sequential, readable ownership."""

from dataclasses import replace

from langchain.agents.middleware import (
    AgentMiddleware,
    ExtendedModelResponse,
    hook_config,
)
from langchain_core.messages import RemoveMessage
from langchain_core.runnables import RunnableLambda
from langgraph.graph.message import REMOVE_ALL_MESSAGES

from pycode.agent.adapters.boundaries import deliver_input, prepare_model_boundary, save_message
from pycode.agent.adapters.state import PycodeState
from pycode.agent.domain.turn_policy import next_turn
from pycode.kernel import ModelError, data_hash, plain_data


def take_queued(context):
    # The agent loop has already settled with no tool calls. Queued input is delivered after
    # any completed loop, so no stop reason is applied here.
    decision = next_turn(
        None,
        (),
        steering_pending=context.inputs.has("steer"),
        follow_up_pending=context.inputs.has("follow_up"),
    )
    if decision.queue is None:
        return None, ()
    return decision.queue, context.inputs.take(decision.queue, one_at_a_time=context.one_at_a_time)


class HistoryMiddleware(AgentMiddleware):
    state_schema = PycodeState

    def __init__(self, bindings):
        self.bindings = bindings

    async def abefore_agent(self, state, runtime):
        context = runtime.context
        await self.bindings.run_hook(context, "session_start", context.run_id)
        await context.events.emit("agent_start", {})
        await context.events.emit("turn_start", {"turn": 1})
        for message in context.initial_inputs:
            await context.events.emit("message_start", plain_data(message))
            await context.events.emit("message_end", plain_data(message))

    async def abefore_model(self, state, runtime):
        context = runtime.context
        if context.model_fallback is not None:
            context.model_fallback.restore_primary(context)

        async def maintain_context(current):
            if current.mcp is not None:
                await current.mcp.refresh(current.registry)
            summary = None
            if current.summary is not None:
                current.summary.set_tools(current.model_tools or current.registry.model_specs())
                summary = await current.summary.prepare()
                if summary.applied_job_id is not None:
                    await self.bindings.run_hook(current, "compact", summary.applied_job_id)
            if current.eviction is not None:
                await current.eviction.apply(
                    protected_entry_ids=summary.protected_entry_ids if summary else ()
                )
            return current

        async def deliver_steering(current):
            if current.team is not None:
                await current.team.flush_inbox(current)
            if current.durable_inputs is not None:
                current.inputs = await current.durable_inputs.pending(current.run_id)
            pending = current.inputs.take("steer", one_at_a_time=current.one_at_a_time)
            for message in pending:
                await deliver_input(current, message, "steer")
            return pending

        async def project_turn(pending):
            turn = state.get("turn", 0) + 1
            await self.bindings.run_hook(context, "turn_start", f"{context.run_id}:turn-{turn}")
            if turn > 1:
                await context.events.emit("turn_start", {"turn": turn})
            updates = {"turn": turn}
            if context.history_store is not None:
                snapshot = await context.history_store.snapshot()
                projection = context.projector.project(snapshot)
                context.model_source_leaf_id = snapshot.leaf_id
                messages = [RemoveMessage(id=REMOVE_ALL_MESSAGES)]
                for message in projection.messages:
                    messages.append(self.bindings.to_framework(message, context.selection))
                updates.update(messages=messages, branch_leaf_id=snapshot.leaf_id)
            elif pending:
                updates["messages"] = [
                    self.bindings.to_framework(message, context.selection) for message in pending
                ]
            return updates

        chain = (
            RunnableLambda(maintain_context)
            | RunnableLambda(deliver_steering)
            | RunnableLambda(project_turn)
        )
        return await chain.ainvoke(context)

    async def awrap_model_call(self, request, handler):
        context = request.runtime.context
        boundary = f"{context.run_id}:model-{request.state['turn']}"
        await self.bindings.run_hook(context, "pre_send", boundary)
        projection = None
        if context.history_store is not None:
            snapshot = await context.history_store.snapshot()
            projection = context.projector.project(snapshot)
            context.model_source_leaf_id = snapshot.leaf_id
            request = request.override(
                messages=[
                    self.bindings.to_framework(message, context.selection)
                    for message in projection.messages
                ]
            )
        tools = list(request.tools)
        names = set()
        for tool in tools:
            if not isinstance(tool, dict):
                names.add(tool.name)
        for spec in context.registry.model_specs():
            if spec.name not in names:
                tools.append(self.bindings.make_framework_tool(spec, context))
        request = request.override(
            tools=tools, model_settings={**request.model_settings, "stream": True}
        )
        if not context.skill_boundary:
            request = await prepare_model_boundary(request, self.bindings)
        requests = context.model_fallback or context.requests
        model_step = self.bindings.model_request_step(requests, handler, logical_id=boundary)
        try:
            response = await model_step.ainvoke(request)
        except ModelError as error:
            if error.code != "context_overflow" or context.summary is None:
                raise
            report = await context.summary.prepare(force=True)
            if report.applied_job_id is None:
                raise
            snapshot = await context.history_store.snapshot()
            projection = context.projector.project(snapshot)
            context.model_source_leaf_id = snapshot.leaf_id
            request = request.override(
                messages=[
                    self.bindings.to_framework(message, context.selection)
                    for message in projection.messages
                ]
            )
            response = await model_step.ainvoke(request)
        model_response = (
            response.model_response if isinstance(response, ExtendedModelResponse) else response
        )
        if context.history_store is not None:
            snapshot = await context.history_store.snapshot()
            projection = context.projector.project(snapshot)
            context.model_source_leaf_id = snapshot.leaf_id
        assistant = model_response.result[-1]
        message = self.bindings.from_assistant(
            assistant, context.selection, context.session_id, context.run_id
        )
        message = replace(
            message,
            provider_metadata={
                **message.provider_metadata,
                "pycode_run_id": context.run_id,
                "pycode_turn": request.state["turn"],
                "pycode_source_leaf_id": context.model_source_leaf_id,
                "pycode_summary_config_hash": context.summary.config_hash(projection)
                if context.summary is not None and projection is not None
                else None,
            },
        )
        await save_message(context, message)
        context.assistant = message
        context.call_order = [call.id for call in message.tool_calls]
        signatures = set()
        context.serial_failure_signature = None
        failed_signature = None
        if context.failure_guard.get("failure_count", 0) > 0:
            failed_signature = (
                context.failure_guard["failure_tool"]
                + ":"
                + context.failure_guard["failure_arguments"]
            )
        for call in message.tool_calls:
            signature = call.name + ":" + data_hash(call.arguments)
            if signature in signatures and signature == failed_signature:
                context.serial_failure_signature = signature
            signatures.add(signature)
        context.dispatched_calls.clear()
        context.interrupted_calls.clear()
        context.ready_results.clear()
        context.settled_calls.clear()
        context.turn_end_sent = False
        await self.bindings.run_hook(context, "post_receive", message.id, message=message.text)
        if assistant.invalid_tool_calls:
            calls = list(assistant.tool_calls)
            for call in message.tool_calls:
                if call.parse_error:
                    calls.append(
                        {"type": "tool_call", "id": call.id, "name": call.name, "args": {}}
                    )
            normalized = assistant.model_copy(
                update={"tool_calls": calls, "invalid_tool_calls": []}
            )
            updated = replace(model_response, result=[*model_response.result[:-1], normalized])
            if isinstance(response, ExtendedModelResponse):
                return replace(response, model_response=updated)
            return updated
        return response

    async def aafter_model(self, state, runtime):
        # This hook records a boundary only. Deep Agents decides all tool routing.
        context = runtime.context
        message = context.assistant
        outcome = "running" if message.tool_calls else "completed"
        if message.stop_reason in {"error", "aborted", "content_filter"}:
            outcome = message.stop_reason
        elif message.stop_reason == "length" and not message.tool_calls:
            outcome = "output_limit"
        if not message.tool_calls:
            await context.events.emit("turn_end", {"turn": state["turn"], "message_id": message.id})
            await self.bindings.run_hook(context, "turn_end", message.id, message=message.text)
        return {"outcome": outcome, "final_text": message.text}

    @hook_config(can_jump_to=["model"])
    async def aafter_agent(self, state, runtime):
        context = runtime.context
        if context.team is not None:
            await context.team.flush_inbox(context)
        if context.summary is not None:
            await context.summary.prepare(wait_for_capacity=False)
        if context.durable_inputs is not None:
            context.inputs = await context.durable_inputs.pending(context.run_id)
        kind, pending = take_queued(context)
        if not pending:
            await self.bindings.run_hook(
                context, "session_end", context.run_id, message=state.get("final_text", "")
            )
            if context.durable_inputs is not None:
                context.inputs = await context.durable_inputs.finish_boundary(context.run_id)
                kind, pending = take_queued(context)
        if pending:
            framework = []
            for message in pending:
                await deliver_input(context, message, kind)
                framework.append(self.bindings.to_framework(message, context.selection))
            return {"messages": framework, "outcome": "running", "jump_to": "model"}
        await context.events.emit("agent_end", {"outcome": state.get("outcome", "completed")})
