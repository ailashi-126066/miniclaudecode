"""One official selector runs after disclosure/permissions, before final budget accounting."""

from dataclasses import dataclass

from langchain.agents.middleware import LLMToolSelectorMiddleware
from langchain_core.exceptions import OutputParserException

from pycode.core.errors import ModelError, ToolError
from pycode.core.hashing import data_hash
from pycode.core.session import EntryDraft
from pycode.core.token_estimate import estimate_text
from pycode.core.values import json_dumps

REQUIRED_TOOLS = frozenset(
    {
        "ls",
        "read_file",
        "glob",
        "grep",
        "ReadToolResult",
        "SkillSearch",
        "AskUserQuestion",
        "ExitPlanMode",
        "check_async_task",
        "list_async_tasks",
        "manage_user_memory",
        "search_user_memory",
        "manage_project_memory",
        "search_project_memory",
    }
)


@dataclass(frozen=True)
class SelectedTools:
    declaration_hash: str
    names: tuple[str, ...]


def tool_declarations(tools):
    declarations = []
    for tool in tools:
        if isinstance(tool, dict):
            declarations.append(tool)
        else:
            schema = tool.tool_call_schema
            if not isinstance(schema, dict):
                schema = schema.model_json_schema()
            declarations.append(
                {"name": tool.name, "description": tool.description, "schema": schema}
            )
    return declarations


class ToolSelector:
    def __init__(self, settings, model_factory):
        self.settings = settings
        self.model_factory = model_factory
        self.snapshots = {}

    async def select(self, request):
        context = request.runtime.context
        boundary = f"{context.run_id}:model-{request.state['turn']}"
        declarations = tool_declarations(request.tools)
        declaration_hash = data_hash(declarations)
        previous = self.snapshots.get(boundary)
        if previous is not None:
            if previous.declaration_hash != declaration_hash:
                raise ModelError(
                    "tool_selection_changed", "Declared tools changed during a logical request"
                )
            context.selected_tool_names = previous.names
            return self.apply(request, previous.names)
        self.snapshots.clear()
        context.selected_tool_names = None
        always = REQUIRED_TOOLS | set(self.settings.always_include)
        if (
            context.plans is not None
            and context.plans.state is not None
            and (context.plans.state.mode == "planning")
        ):
            always |= {"write_file", "edit_file", "ExitPlanMode"}
        candidates = [
            tool for tool in request.tools if not isinstance(tool, dict) and tool.name not in always
        ]
        schema_tokens = estimate_text(json_dumps(declarations, ensure_ascii=False))
        if (
            not self.settings.enabled
            or len(candidates) <= self.settings.max_tools
            or (
                len(candidates) < self.settings.candidate_threshold
                and schema_tokens < self.settings.schema_token_threshold
            )
        ):
            return request
        required = [
            tool.name
            for tool in request.tools
            if not isinstance(tool, dict) and tool.name in always
        ]
        allowed_names = {tool.name for tool in candidates}

        def validate_response(response):
            if response.tool_calls:
                selected = response.tool_calls[0]["args"].get("tools")
                if isinstance(selected, list):
                    if any(
                        not isinstance(name, str) or name not in allowed_names for name in selected
                    ):
                        raise ToolError(
                            "invalid_tool_selection", "Selection contains unavailable tools"
                        )
                    if len(set(selected)) != len(selected):
                        raise ToolError(
                            "invalid_tool_selection", "Selection contains duplicate tools"
                        )

        owner = None
        selected_request = request
        status, reason = "selected", None
        try:
            owner = self.model_factory(context, boundary, validate_response)
            selector = LLMToolSelectorMiddleware(
                model=owner.model,
                max_tools=self.settings.max_tools,
                always_include=required,
                max_retries=0,
                on_parsing_failure="error",
            )

            async def capture(current):
                return current

            selected_request = await selector.awrap_model_call(request, capture)
        except (ModelError, ToolError, OutputParserException, ValueError) as error:
            if isinstance(error, ValueError) and not isinstance(error, OutputParserException):
                if not str(error).startswith(
                    "LLMToolSelectorMiddleware: selection model returned a malformed"
                ):
                    raise
            status = "fallback"
            reason = (
                error.code
                if isinstance(error, (ModelError, ToolError))
                else "invalid_tool_selection"
            )
            if self.settings.on_failure == "error":
                await self.record(
                    context,
                    boundary,
                    declaration_hash,
                    (),
                    "failed",
                    reason,
                    schema_tokens,
                    schema_tokens,
                )
                raise ModelError(
                    "tool_selection_failed", "Tool selection failed; main request was not sent"
                ) from error
        finally:
            if owner is not None:
                await owner.aclose()
        names = tuple(tool.name for tool in selected_request.tools if not isinstance(tool, dict))
        self.snapshots[boundary] = SelectedTools(declaration_hash, names)
        context.selected_tool_names = names
        after_tokens = estimate_text(
            json_dumps(tool_declarations(selected_request.tools), ensure_ascii=False)
        )
        await self.record(
            context, boundary, declaration_hash, names, status, reason, schema_tokens, after_tokens
        )
        return selected_request

    def apply(self, request, names):
        by_name = {}
        provider_tools = []
        for tool in request.tools:
            if isinstance(tool, dict):
                provider_tools.append(tool)
            else:
                by_name[tool.name] = tool
        selected = [by_name[name] for name in names]
        return request.override(tools=[*selected, *provider_tools])

    async def record(
        self,
        context,
        boundary,
        declaration_hash,
        names,
        status,
        reason,
        before_tokens,
        after_tokens,
    ):
        data = {
            "kind": "tool_selection",
            "logical_id": boundary,
            "declaration_hash": declaration_hash,
            "names": list(names),
            "status": status,
            "error_code": reason,
            "schema_estimate_before": before_tokens,
            "schema_estimate_after": after_tokens,
        }
        if context.history_store is not None:
            await context.history_store.append_entry(
                EntryDraft("custom", data=data, run_id=context.run_id)
            )
        await context.events.emit("notification", data)


async def select_model_tools(request):
    selector = request.runtime.context.tool_selector
    return await selector.select(request) if selector is not None else request
