"""Record the effective model footprint before each request or fallback candidate."""

from collections.abc import Callable
from typing import cast

from pycode.kernel import (
    EntryDraft,
    ExecutableTool,
    SessionTree,
    StorageError,
    ToolError,
    ToolSpec,
    data_hash,
    plain_data,
)
from pycode.models.ports.context import CalibrationContext


def model_tool_specs(
    tools,
    context: CalibrationContext,
    *,
    resource_lookup: Callable[[str], ExecutableTool | None] | None = None,
) -> tuple[ToolSpec, ...]:
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
            resource = resource_lookup(tool.name) if resource_lookup is not None else None
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


async def calibrate_model_request(
    request,
    *,
    converter,
    estimate_text,
    resource_lookup: Callable[[str], ExecutableTool | None] | None = None,
):
    context = cast(CalibrationContext, request.runtime.context)
    actual_tools = model_tool_specs(request.tools, context, resource_lookup=resource_lookup)
    # Execution authorization uses the final SDK-disclosed request, even when
    # the optional selector is disabled. Catalog candidates never count.
    context.selected_tool_names = tuple(spec.name for spec in actual_tools)
    system = request.system_message.content if request.system_message is not None else ""
    system_hash = data_hash(system)
    if actual_tools != context.model_tools or system_hash != context.system_hash:
        context.model_tools = actual_tools
        context.system_hash = system_hash
        history = context.history_store
        if history is not None:
            snapshot = await history.snapshot()
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
                await history.append_entry(
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
        summary = context.summary
        if summary is not None:
            summary.set_tools(actual_tools)
            report = await summary.prepare()
        if history is not None:
            projector = context.projector
            if projector is None:
                raise StorageError(
                    "projector_unavailable", "Calibration history requires a projector"
                )
            snapshot = await history.snapshot()
            projection = projector.project(snapshot)
            context.model_source_leaf_id = snapshot.leaf_id
            if report is not None and report.applied_job_id is not None:
                request = request.override(
                    messages=[
                        converter(message, context.selection) for message in projection.messages
                    ]
                )
    return request
