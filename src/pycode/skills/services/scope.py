"""Skill-owned tool restrictions, independent of the parent permission policy."""

from pycode.kernel import SessionTree, ToolError
from pycode.skills.domain.skills import skill_scope

ALIASES = {
    "Read": "read_file",
    "Write": "write_file",
    "Edit": "edit_file",
    "Grep": "grep",
    "Glob": "glob",
}


class SkillsScopeConstraint:
    def __init__(self, history):
        self.history = history

    async def allowed(self):
        snapshot = await self.history.snapshot()
        scope = skill_scope(SessionTree(snapshot.index).path(snapshot.leaf_id))
        if scope is None:
            return None
        if any(not isinstance(name, str) for name in scope):
            raise ToolError("invalid_skill_scope", "Skill tool names must be strings")
        return {ALIASES.get(name, name) for name in scope} | {"SkillSearch"}

    async def authorize(self, context, spec, call):
        await self.check_call(call)

    async def check_call(self, call):
        allowed = await self.allowed()
        instruction_read = call.name == "read_file" and call.arguments.get(
            "file_path", ""
        ).startswith("/skills/")
        if allowed is not None and call.name not in allowed and not instruction_read:
            raise ToolError("skill_tool_not_allowed", "Active Skill scope excludes this tool")

    async def filter_request(self, request):
        allowed = await self.allowed()
        if allowed is None:
            return request
        tools = []
        for tool in request.tools:
            name = (
                tool.name if not isinstance(tool, dict) else tool.get("function", tool).get("name")
            )
            if name in allowed or name == "read_file":
                # Loading/reloading instructions remains possible even when the
                # active Skill excludes ordinary workspace reads. authorize()
                # still permits only the exact Skill-path exception.
                tools.append(tool)
        return request.override(tools=tools)
