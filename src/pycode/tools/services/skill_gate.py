"""Permission gate composed from injected feature state and constraints."""

from pycode.kernel import SessionTree, ToolError
from pycode.skills import skill_scope

CONTROL_TOOLS = frozenset({"SkillSearch"})


class SkillToolGate:
    def __init__(self, history, parent):
        self._history = history
        self._parent = parent

    async def authorize(self, context, spec, call):
        snapshot = await self._history.snapshot()
        allowed = skill_scope(SessionTree(snapshot.index).path(snapshot.leaf_id))
        if allowed is not None:
            aliases = {
                "Read": "read_file",
                "Write": "write_file",
                "Edit": "edit_file",
                "Grep": "grep",
                "Glob": "glob",
            }
            allowed = {aliases.get(name, name) for name in allowed}
            skill_read = spec.name == "read_file" and call.arguments.get(
                "file_path", ""
            ).startswith("/skills/")
            if spec.name not in allowed and spec.name not in CONTROL_TOOLS and not skill_read:
                raise ToolError("skill_tool_not_allowed", "Active Skill scope excludes this tool")
        await self._parent.authorize(context, spec, call)
