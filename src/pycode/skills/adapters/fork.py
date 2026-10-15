"""Skill fork instructions and inherited context, independent of Team assembly."""

from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda

from pycode.kernel import SessionTree, ToolError


class ForkSkillDelegate:
    def __init__(self, run_blocking, parent_history, entry, path):
        self.run_blocking, self.history, self.entry, self.path = (
            run_blocking,
            parent_history,
            entry,
            path,
        )

    async def invoke(self, state):
        snapshot = await self.history.snapshot()
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        arguments = ""
        for saved in reversed(path):
            if saved.message is None or saved.message.role != "user":
                continue
            selected = saved.message.provider_metadata.get("skill_request", {})
            if selected.get("name") == self.entry.name:
                arguments = selected.get("arguments", "")
                break
        inherited, source_ids = None, []
        if self.entry.context != "none":
            source_leaf = snapshot.leaf_id
            for saved in reversed(path):
                if saved.message is not None and any(
                    call.name == "task" for call in saved.message.tool_calls
                ):
                    source_leaf = saved.parent_id
                    break
            inherited = (snapshot.header.session_id, source_leaf)
            if self.entry.context == "recent":
                for saved in SessionTree(snapshot.index).path(source_leaf):
                    if saved.data.get("kind") == "run_start":
                        source_ids = []
                    if saved.type == "message" and saved.require_message().role != "system":
                        source_ids.append(saved.id)
        messages = state.get("messages", [])
        if not messages or not isinstance(messages[-1].content, str):
            raise ToolError("empty_subagent_task", "Subagent needs a text task")
        instruction = (
            "Read " + self.path + " and use its Skill instructions.\n" + messages[-1].content
        )
        instruction += "\nTask arguments: " + arguments
        result = await self.run_blocking(
            instruction, fork_from=inherited, fork_entry_ids=source_ids or None
        )
        if result["status"] != "completed":
            raise ToolError("subagent_failed", "Readonly delegate ended with " + result["status"])
        return {"messages": [AIMessage(content=result["final_text"])]}

    def spec(self):
        return {
            "name": "skill-" + self.entry.name,
            "description": self.entry.description,
            "mode": "fork",
            "runnable": RunnableLambda(self.invoke),
        }
