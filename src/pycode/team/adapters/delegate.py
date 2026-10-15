"""Adapt synchronous readonly Team delegation to the native framework response."""

from langchain_core.messages import AIMessage

from pycode.kernel import ToolError


class TeamDelegate:
    def __init__(self, team, provider, parent_history, run_id=None):
        self.team, self.provider, self.parent_history = team, provider, parent_history
        self.run_id = run_id

    async def invoke(self, state):
        messages = state.get("messages", [])
        if not messages or not isinstance(messages[-1].content, str):
            raise ToolError("empty_subagent_task", "Subagent needs a text task")
        result = await self.team.run_blocking(
            messages[-1].content, self.provider, run_id=self.run_id
        )
        if result["status"] != "completed":
            raise ToolError("subagent_failed", "Readonly delegate ended with " + result["status"])
        return {"messages": [AIMessage(content=result["final_text"])]}
