"""Local team configuration and synchronous fork Skill/Hook adaptation."""

from dataclasses import replace
from pathlib import Path

from langchain_core.messages import AIMessage

from pycode.kernel import ToolError


def child_config(config, record):
    return replace(
        config,
        workspace=Path(record["workspace"]),
        read_only=record["mode"] == "readonly",
        isolated_worker=record["mode"] == "worker",
        allow_delegation=False,
        agent_id="agent:" + record["agent_id"],
        memory_mode="off",
        sandbox=replace(config.sandbox, mode="on")
        if record["mode"] == "worker"
        else config.sandbox,
        credential_workspace=config.credential_workspace or config.workspace,
    )


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
