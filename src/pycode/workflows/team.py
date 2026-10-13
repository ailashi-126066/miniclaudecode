"""Invoke public Deep Agents Async Subagents tools outside a model turn."""

from uuid import uuid4

from deepagents.middleware.async_subagents import AsyncSubAgentMiddleware
from langchain.tools import ToolRuntime
from langgraph.types import Command

from pycode.core.errors import ToolError


class NativeTeamCommands:
    def __init__(self, specifications):
        self.middleware = AsyncSubAgentMiddleware(async_subagents=list(specifications))
        self.tools = {}
        for tool in self.middleware.tools:
            self.tools[tool.name] = tool

    async def execute(self, name, arguments, tasks):
        tool = self.tools.get(name)
        if tool is None:
            raise ToolError("unknown_team_command", "Unknown native Team command")
        validated = tool.args_schema.model_validate(arguments).model_dump()
        runtime = ToolRuntime(
            {"messages": [], "async_tasks": tasks}, None, {}, discard_progress, uuid4().hex, None
        )
        response = await tool.coroutine(runtime=runtime, **validated)
        if not isinstance(response, Command):
            raise ToolError("team_service_unavailable", str(response))
        return response.update.get("async_tasks", {}), response.update["messages"][0].content


def discard_progress(value):
    return None
