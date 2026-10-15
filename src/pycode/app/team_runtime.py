"""Worker ownership uses existing sessions and their single Deep Agents harness."""

import asyncio
import json
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from langchain_core.messages import AIMessage

from pycode.core.errors import ToolError
from pycode.core.messages import Message
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.values import json_dumps


class SessionWorker:
    def __init__(self, session, worktrees=None, worktree_id=None):
        self.session = session
        self.worktrees = worktrees
        self.worktree_id = worktree_id

    async def run(self, instruction):
        if instruction.lstrip().startswith("{"):
            try:
                command = json.loads(instruction)
            except ValueError:
                command = None
            if isinstance(command, dict) and set(command) == {"approval"}:
                return await self.session.resume(command["approval"])
        await self.session.cancel()
        return await self.session.run(Message(uuid4().hex, "user", instruction))

    async def cancel(self):
        return await self.session.cancel()

    async def values(self):
        view = await self.session.inspect()
        messages = []
        for entry in view["snapshot"]["entries"]:
            message = entry.get("message")
            if message is not None and message["role"] in {"user", "assistant"}:
                messages.append({"role": message["role"], "content": message["text"]})
        if messages:
            metadata = {"session_id": view["snapshot"]["header"]["session_id"]}
            if self.worktree_id is not None:
                metadata["worktree"] = await self.worktrees.inspect(self.worktree_id)
            messages[-1] = {
                **messages[-1],
                "content": messages[-1]["content"]
                + "\n\nWorker references: "
                + json_dumps(metadata, ensure_ascii=False),
            }
        return {"messages": messages, "worktree_id": self.worktree_id}

    async def aclose(self):
        try:
            await self.session.aclose()
        finally:
            if self.worktree_id is not None:
                record = await self.worktrees.inspect(self.worktree_id)
                record["worker_stopped"] = True
                await self.worktrees.update(record)


class ConfiguredWorkers:
    def __init__(
        self,
        config,
        providers,
        session_opener,
        worktrees,
        request_database,
        consumers=(),
        session_forker=None,
    ):
        self.config = config
        self.providers = providers
        self.session_opener = session_opener
        self.worktrees = worktrees
        self.request_database = request_database
        self.consumers = consumers
        self.session_forker = session_forker

    async def create(
        self, thread_id, graph_id, *, fork_from=None, base="HEAD", include_uncommitted=False
    ):
        if graph_id not in {"readonly", "worker", "integration"}:
            raise ToolError(
                "unknown_worker_role", "Worker role must be readonly, worker or integration"
            )
        worktree = None
        if graph_id == "worker":
            worktree = await self.worktrees.create(
                base=base, include_uncommitted=include_uncommitted
            )
        elif base != "HEAD" or include_uncommitted:
            raise ToolError(
                "worktree_role_required", "Start options require a modifying worktree Worker"
            )
        config = replace(
            self.config,
            workspace=Path(worktree["path"]) if worktree else self.config.workspace,
            read_only=graph_id == "readonly",
            allow_delegation=False,
            team_agents=(),
            agent_id="worker:" + thread_id,
            request_database=self.request_database,
            memory_mode="off",
            isolated_worker=graph_id == "worker",
            credential_workspace=self.config.credential_workspace or self.config.workspace,
        )
        if fork_from is not None:
            if self.session_forker is None or graph_id != "readonly":
                raise ToolError(
                    "fork_unavailable", "Only read-only sessions can inherit this history"
                )
            parent_id, leaf_id = fork_from
            created = await self.session_forker(parent_id, leaf_id, "worker-" + thread_id)
            await created.aclose()
        try:
            session = await self.session_opener(
                config,
                self.providers,
                "worker-" + thread_id,
                create=True,
                consumers=self.consumers,
                refresh_models=False,
                startup_hooks=False,
            )
        except BaseException:
            if worktree is not None:
                worktree.update(status="worker_start_failed", worker_stopped=True)
                await self.worktrees.update(worktree)
            raise
        return SessionWorker(session, self.worktrees, worktree["id"] if worktree else None)


class SynchronousDelegate:
    def __init__(self, workers, parent_history, run_id=None, skill=None, skill_path=None):
        self.workers = workers
        self.parent_history = parent_history
        self.run_id = run_id
        self.skill = skill
        self.skill_path = skill_path

    async def invoke(self, state):
        identifier = uuid4().hex
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
        await self.parent_history.append_entry(
            EntryDraft(
                "team_delegate",
                run_id=self.run_id,
                data={
                    "session_id": "worker-" + identifier,
                    "agent_id": identifier,
                    "status": "running",
                    "role": "readonly",
                },
            )
        )
        worker = None
        settled = False
        try:
            worker = await self.workers.create(identifier, "readonly", fork_from=inherited)
            if self.skill is not None and self.skill.context == "recent":
                await worker.session.configure_fork_context(source_ids)
            messages = state.get("messages", [])
            if not messages:
                raise ToolError("empty_subagent_task", "Subagent needs a task description")
            instruction = messages[-1].content
            if not isinstance(instruction, str):
                raise ToolError("invalid_subagent_task", "Subagent instruction must be text")
            if self.skill is not None:
                instruction = (
                    "Read " + self.skill_path + " and use its Skill instructions.\n" + instruction
                )
                instruction += "\nTask arguments: " + skill_arguments
            result = await worker.run(instruction)
            await self.parent_history.append_entry(
                EntryDraft(
                    "team_delegate",
                    run_id=self.run_id,
                    data={
                        "session_id": "worker-" + identifier,
                        "agent_id": identifier,
                        "status": result.outcome,
                    },
                )
            )
            settled = True
            if result.outcome != "completed":
                raise ToolError(
                    "subagent_failed", "Read-only subagent ended with " + result.outcome
                )
            return {"messages": [AIMessage(content=result.final_text)]}
        except BaseException as error:
            if not settled:
                status = "cancelled" if isinstance(error, asyncio.CancelledError) else "failed"
                await self.parent_history.append_entry(
                    EntryDraft(
                        "team_delegate",
                        run_id=self.run_id,
                        data={
                            "session_id": "worker-" + identifier,
                            "agent_id": identifier,
                            "status": status,
                        },
                    )
                )
            raise
        finally:
            if worker is not None:
                await worker.aclose()
