"""Deep Agents boundary extracted for sequential, readable ownership."""

from langchain_core.callbacks import AsyncCallbackHandler
from langchain_core.runnables import RunnableLambda
from langchain_core.tools import StructuredTool

from pycode.core.errors import ToolError
from pycode.core.hashing import data_hash
from pycode.core.hooks import HookContext
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.skills import skill_scope
from pycode.core.token_estimate import estimate_text
from pycode.core.tools import ToolContext, ToolSpec
from pycode.core.values import plain_data
from pycode.workflows.messages import to_framework
from pycode.workflows.tool_selection import select_model_tools


async def run_hook(context, event, boundary, *, message="", error=""):
    if context.hooks is not None:
        await context.hooks.fire(
            HookContext(
                context.session_id,
                context.run_id,
                context.workspace,
                boundary,
                event,
                message=message,
                error=error,
            )
        )


def model_tool_specs(tools, context):
    specs = []
    for tool in tools:
        if isinstance(tool, dict):
            function = tool.get("function", tool)
            specs.append(
                ToolSpec(
                    function.get("name", tool.get("type", "provider_tool")),
                    function.get("description", "Provider tool"),
                    function.get("parameters", {}),
                    source="provider",
                )
            )
            continue
        try:
            spec = context.registry.get_spec(tool.name)
        except ToolError:
            resource = (
                context.skill_resources.get_tool(tool.name) if context.skill_resources else None
            )
            if resource is not None:
                spec = resource.spec
            else:
                schema = tool.tool_call_schema
                if not isinstance(schema, dict):
                    schema = schema.model_json_schema()
                spec = ToolSpec(tool.name, tool.description, schema, source="deepagents")
        specs.append(spec)
    specs.sort(key=lambda spec: spec.name)
    return tuple(specs)


async def prepare_model_boundary(request):
    chain = (
        RunnableLambda(filter_model_tools)
        | RunnableLambda(select_model_tools)
        | RunnableLambda(calibrate_model_request)
    )
    return await chain.ainvoke(request)


async def filter_model_tools(request):
    context = request.runtime.context
    if context.history_store is not None:
        snapshot = await context.history_store.snapshot()
        scope = skill_scope(SessionTree(snapshot.index).path(snapshot.leaf_id))
        if scope is not None:
            aliases = {
                "Read": "read_file",
                "Write": "write_file",
                "Edit": "edit_file",
                "Grep": "grep",
                "Glob": "glob",
            }
            allowed = {"SkillSearch"}
            for name in scope:
                allowed.add(aliases.get(name, name))
            filtered = []
            for tool in request.tools:
                name = (
                    tool.name
                    if not isinstance(tool, dict)
                    else tool.get("function", tool).get("name")
                )
                if name in allowed:
                    filtered.append(tool)
            request = request.override(tools=filtered)
    selector = context.tool_selector
    if selector is not None and selector.settings.enabled:
        planning = (
            context.plans is not None
            and context.plans.state is not None
            and (
                context.plans.state.mode == "planning"
                or context.plans.state.approved_run_id != context.run_id
                and context.plans.state.mode != "completed"
            )
        )
        if context.read_only or planning:
            readonly_native = {
                "ls",
                "read_file",
                "glob",
                "grep",
                "write_todos",
                "task",
            }
            permitted = []
            for tool in request.tools:
                if isinstance(tool, dict):
                    continue
                try:
                    category = context.registry.get_spec(tool.name).category
                except ToolError:
                    category = "read" if tool.name in readonly_native else "external"
                if (
                    category == "read"
                    or planning
                    and not context.read_only
                    and tool.name
                    in {
                        "write_file",
                        "edit_file",
                    }
                ):
                    permitted.append(tool)
            request = request.override(tools=permitted)
    return request


async def calibrate_model_request(request):
    context = request.runtime.context
    actual_tools = model_tool_specs(request.tools, context)
    system = request.system_message.content if request.system_message is not None else ""
    system_hash = data_hash(system)
    if actual_tools != context.model_tools or system_hash != context.system_hash:
        context.model_tools = actual_tools
        context.system_hash = system_hash
        if context.history_store is not None:
            snapshot = await context.history_store.snapshot()
            previous = None
            for entry in reversed(SessionTree(snapshot.index).path(snapshot.leaf_id)):
                if (
                    entry.type == "tool_set_change"
                    and entry.data.get("source") == "effective_model"
                ):
                    previous = entry
                    break
            digest = data_hash({"tools": plain_data(actual_tools), "system_hash": system_hash})
            if previous is None or previous.data["hash"] != digest:
                await context.history_store.append_entry(
                    EntryDraft(
                        "tool_set_change",
                        data={
                            "source": "effective_model",
                            "hash": digest,
                            "tools": plain_data(actual_tools),
                            "harness_system_hash": system_hash,
                            "harness_system_tokens": estimate_text(
                                system if isinstance(system, str) else str(system)
                            ),
                        },
                        run_id=context.run_id,
                    )
                )
        report = None
        if context.summary is not None:
            context.summary.set_tools(actual_tools)
            report = await context.summary.prepare()
        if context.history_store is not None:
            snapshot = await context.history_store.snapshot()
            projection = context.projector.project(snapshot)
            context.model_source_leaf_id = snapshot.leaf_id
            if report is not None and report.applied_job_id is not None:
                request = request.override(
                    messages=[
                        to_framework(message, context.selection) for message in projection.messages
                    ]
                )
    return request


class ModelEventCallbacks(AsyncCallbackHandler):
    def __init__(self, events, requests, request_source=None):
        self.events = events
        self.requests = requests
        self.request_source = request_source

    def current_request(self):
        return self.request_source() if self.request_source is not None else self.requests

    async def on_chat_model_start(self, serialized, messages, *, run_id, **kwargs):
        if "pycode:internal" in kwargs.get("tags", ()):
            return
        await self.events.emit(
            "message_start",
            {
                "role": "assistant",
                "request_id": self.current_request().active_request_id,
                "model_selection": plain_data(self.current_request().selection),
                "provider_run_id": str(run_id),
            },
        )

    async def on_llm_new_token(self, token, *, chunk=None, **kwargs):
        if "pycode:internal" in kwargs.get("tags", ()):
            return
        if token:
            await self.events.emit(
                "message_delta",
                {"text": token, "request_id": self.current_request().active_request_id},
            )
        if chunk is None:
            return
        reasoning = chunk.message.additional_kwargs.get("reasoning_content")
        if reasoning:
            await self.events.emit(
                "thinking_delta",
                {"text": reasoning, "request_id": self.current_request().active_request_id},
            )
        if isinstance(chunk.message.content, list):
            for block in chunk.message.content:
                if isinstance(block, dict) and block.get("type") in {"thinking", "reasoning"}:
                    text = block.get("thinking") or block.get("reasoning")
                    if text:
                        await self.events.emit(
                            "thinking_delta",
                            {"text": text, "request_id": self.current_request().active_request_id},
                        )


async def save_message(context, message, *, emit_start=False):
    entry_id = await context.journal.append_message(message)
    result = message.tool_result
    if result is not None and result.output_id and context.outputs is not None:
        await context.outputs.bind(result.output_id, entry_id)
    if (
        result is not None
        and result.details.get("file_snapshot")
        and context.history_store is not None
    ):
        await context.history_store.append_entry(
            EntryDraft(
                "custom",
                run_id=context.run_id,
                operation_id=result.operation_id,
                data={
                    "kind": "file_snapshot",
                    "message_entry_id": entry_id,
                    **result.details["file_snapshot"],
                },
            )
        )
    context.completed.append(message)
    if emit_start:
        await context.events.emit("message_start", plain_data(message))
    await context.events.emit("message_end", {**plain_data(message), "entry_id": entry_id})
    return entry_id


async def deliver_input(context, message, kind):
    entry_id = await save_message(context, message, emit_start=True)
    await context.events.emit(
        "notification",
        {
            "kind": "input_delivered",
            "input_id": message.id,
            "queue_kind": kind,
            "entry_id": entry_id,
        },
    )


def make_framework_tool(spec, context, tool_context=None, domain_tool=None):
    if context.mcp is not None and context.mcp.framework_tool is not None:
        native = context.mcp.framework_tool(spec.name)
        if native is not None:
            return native

    async def execute(**arguments):
        actual_context = tool_context or ToolContext(
            context.session_id,
            context.run_id,
            "",
            context.workspace,
            tool_name=spec.name,
            agent_id=context.agent_id,
        )
        tool = domain_tool or context.registry.get(spec.name)
        result = await tool.execute(actual_context, arguments)
        return result.text, result

    return StructuredTool(
        name=spec.name,
        description=spec.description,
        args_schema=spec.input_schema,
        coroutine=execute,
        response_format="content_and_artifact",
    )
