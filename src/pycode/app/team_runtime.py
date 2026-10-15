"""Local team configuration and synchronous fork Skill/Hook adaptation."""

from dataclasses import replace
from pathlib import Path

from langchain_core.messages import AIMessage

from pycode.kernel import SessionTree, ToolError


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
    def __init__(self, team, provider, parent_history, run_id=None, skill=None, skill_path=None):
        self.team, self.provider, self.parent_history = team, provider, parent_history
        self.run_id, self.skill, self.skill_path = run_id, skill, skill_path

    async def invoke(self, state):
        inherited = None
        source_ids = []
        skill_arguments = ""
        parent_snapshot = await self.parent_history.snapshot()
        parent_path = SessionTree(parent_snapshot.index).path(parent_snapshot.leaf_id)
        if self.skill is not None:
            for entry in reversed(parent_path):
                if entry.message is None or entry.message.role != "user":
                    continue
                selected = entry.message.provider_metadata.get("skill_request", {})
                if selected.get("name") == self.skill.name:
                    skill_arguments = selected.get("arguments", "")
                    break
        if self.skill is not None and self.skill.context != "none":
            source_leaf = parent_snapshot.leaf_id
            for entry in reversed(parent_path):
                if entry.message is not None and any(
                    call.name == "task" for call in entry.message.tool_calls
                ):
                    source_leaf = entry.parent_id
                    break
            inherited = (parent_snapshot.header.session_id, source_leaf)
            if self.skill.context == "recent":
                recent = []
                for entry in SessionTree(parent_snapshot.index).path(source_leaf):
                    if entry.data.get("kind") == "run_start":
                        recent = []
                    if entry.type == "message" and entry.message.role != "system":
                        recent.append(entry.id)
                source_ids = recent
        messages = state.get("messages", [])
        if not messages or not isinstance(messages[-1].content, str):
            raise ToolError("empty_subagent_task", "Subagent needs a text task")
        instruction = messages[-1].content
        if self.skill is not None:
            instruction = (
                "Read " + self.skill_path + " and use its Skill instructions.\n" + instruction
            )
            instruction += "\nTask arguments: " + skill_arguments
        result = await self.team.run_blocking(
            instruction,
            self.provider,
            run_id=self.run_id,
            fork_from=inherited,
            fork_entry_ids=source_ids or None,
        )
        if result["status"] != "completed":
            raise ToolError("subagent_failed", "Readonly delegate ended with " + result["status"])
        return {"messages": [AIMessage(content=result["final_text"])]}
