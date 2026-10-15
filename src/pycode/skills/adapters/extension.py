"""The Skill loader and durable receipts meet the generic framework boundaries here."""

import json
from dataclasses import replace
from typing import cast

from deepagents.middleware.skills import SkillsState, disclosed_skill_tool_names

from pycode.kernel import ToolError
from pycode.skills.adapters.skills import PycodeSkillsMiddleware


class SkillsFrameworkExtension:
    prepares_model_boundary = True

    def __init__(self, resources, selection, sources, constraint):
        self.resources, self.selection = resources, selection
        self.sources, self.constraint = sources, constraint

    def middleware(self, context, backend, model_boundary, tool_converter):
        async def resolve(group, runtime):
            if self.resources is None:
                return []
            tools = await self.resources.resolve_group(group)
            return [tool_converter(tool.spec, runtime.context, domain_tool=tool) for tool in tools]

        return PycodeSkillsMiddleware(
            selection=self.selection,
            model_boundary=model_boundary,
            backend=backend,
            sources=list(self.sources),
            tools=resolve,
        )

    async def filter_request(self, request):
        return (
            await self.constraint.filter_request(request)
            if self.constraint is not None
            else request
        )


class SkillsToolExtension:
    def __init__(self, resources, recorder, selection):
        self.resources, self.recorder, self.selection = resources, recorder, selection

    def get_tool(self, name):
        return self.resources.get_tool(name)

    def lookup(self, name, *, state):
        # Only the SDK's actual disclosed names count. The catalog contains
        # candidates that must never become executable merely by lookup.
        if name not in disclosed_skill_tool_names(cast(SkillsState, state)):
            return None
        return self.resources.get_tool(name)

    async def before_call(self, context, call):
        if call.name != "read_file" or context.agent_id != "lead":
            return
        path = call.arguments.get("file_path", "")
        if not path.startswith("/skills/"):
            return
        selected = await self.recorder.selected_entry(path)
        if selected is not None and selected.mode == "fork":
            raise ToolError(
                "skill_requires_subagent", "Use task with subagent_type=skill-" + selected.name
            )

    def prepare_result(self, call, result):
        if (
            call.name == "read_file"
            and call.arguments.get("file_path", "").startswith("/skills/")
            and not result.is_error
        ):
            return replace(result, details={**result.details, "protected_content": True})
        return result

    async def after_persist(self, context, call, result, entry_id):
        if result.is_error:
            return
        if call.name == "SkillSearch" and self.selection.dispatch:
            selected = result.structured_content
            if selected is None:
                selected = json.loads(result.text)
            if not isinstance(selected, list):
                raise TypeError("SkillSearch must return a list of skill records")
            self.selection.select([item["path"] for item in selected])
        path = call.arguments.get("file_path", "")
        if call.name == "read_file" and path.startswith("/skills/"):
            await self.recorder.record(path, result, entry_id)
