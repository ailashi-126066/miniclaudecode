"""Dependency declarations use the same native Async Subagents task service."""

from pycode.core.messages import ToolResult
from pycode.core.tools import ToolSpec


class TeamTask:
    spec = ToolSpec(
        "TeamTask",
        "Declare a task with dependencies; starts through native Async Subagents when ready.",
        {
            "type": "object",
            "properties": {
                "agent": {"type": "string"},
                "description": {"type": "string"},
                "dependencies": {"type": "array", "items": {"type": "string"}},
                "base": {"type": "string", "minLength": 1},
                "include_uncommitted": {"type": "boolean", "default": False},
            },
            "required": ["agent", "description"],
            "additionalProperties": False,
        },
        category="external",
        source="team",
    )

    def __init__(self, manager):
        self.manager = manager

    async def execute(self, context, arguments):
        identifier = await self.manager.queue(
            arguments["agent"],
            arguments["description"],
            arguments.get("dependencies", ()),
            base=arguments.get("base"),
            include_uncommitted=arguments.get("include_uncommitted", False),
        )
        return ToolResult(
            context.call_id, self.spec.name, "Dependency task declared: " + identifier
        )
