"""Persist native Async Subagents state; never maintain a Worker message queue."""

import asyncio
import json
from uuid import uuid4

from pycode.core.approvals import validate_operation_answer
from pycode.core.errors import ToolError
from pycode.core.session import EntryDraft
from pycode.core.team import TERMINAL_TASK_STATES, dependencies_ready, validate_dependencies
from pycode.core.values import json_dumps, plain_data


class TeamManager:
    def __init__(self, history, agents=(), *, concurrency=4, commands=None, plans=None):
        if isinstance(concurrency, bool) or not isinstance(concurrency, int) or concurrency < 1:
            raise ValueError("Team concurrency must be a positive integer")
        self.history = history
        self.agents = agents
        self.concurrency = concurrency
        self.lock = asyncio.Lock()
        self.commands = commands
        self.scheduling = asyncio.Lock()
        self.plans = plans

    async def authorize(self, name, arguments):
        if name not in {"start_async_task", "update_async_task", "queue"}:
            return
        role_call = "start_async_task" if name == "queue" else name
        if self.plans is not None and self.plans.state is not None:
            state = self.plans.state
            if state.mode == "planning" and not await self.read_only_call(role_call, arguments):
                raise ToolError("plan_read_only", "Plan mode permits only read-only Workers")

    async def validate_call(self, name, arguments, *, dependencies=(), answering=False):
        await self.authorize(name, arguments)
        if name == "update_async_task" and not answering:
            try:
                message = json.loads(arguments.get("message", ""))
            except (ValueError, TypeError):
                message = None
            if isinstance(message, dict) and set(message) == {"approval"}:
                raise ToolError(
                    "worker_answer_required",
                    "Use the explicit Team answer command for user decisions",
                )
        if name == "start_async_task":
            await self.validate_start(dependencies=dependencies)
        elif name == "update_async_task":
            task = (await self.tasks()).get(arguments.get("task_id"))
            if task is not None and task["status"] in TERMINAL_TASK_STATES:
                await self.validate_start()

    async def tasks(self):
        snapshot = await self.history.snapshot()
        tasks = {}
        for entry in snapshot.entries:
            if entry.type == "team_task":
                task = plain_data(entry.data)
                tasks[task["task_id"]] = task
        return tasks

    async def read_only_call(self, name, arguments):
        role_name = arguments.get("agent") if name == "TeamTask" else arguments.get("subagent_type")
        if name == "update_async_task":
            task = (await self.tasks()).get(arguments.get("task_id"))
            if task is None:
                return False
            role_name = task["agent_name"]
        for agent in self.agents:
            if agent.name == role_name:
                return not agent.modifies_workspace and agent.graph_id not in {
                    "worker",
                    "integration",
                }
        return False

    async def validate_start(self, *, task_id="", dependencies=()):
        tasks = await self.tasks()
        nodes = await self.dependency_nodes(tasks)
        validate_dependencies(task_id, dependencies, nodes)
        if not dependencies_ready(dependencies, nodes):
            raise ToolError("dependencies_pending", "Wait for every dependency to succeed")
        running = 0
        for task in tasks.values():
            if task["status"] not in TERMINAL_TASK_STATES:
                running += 1
        if running >= self.concurrency:
            raise ToolError("team_concurrency_limit", "Team has reached its concurrency limit")

    async def dependency_nodes(self, tasks=None):
        tasks = tasks if tasks is not None else await self.tasks()
        nodes = dict(tasks)
        snapshot = await self.history.snapshot()
        pending = {}
        for entry in snapshot.entries:
            if entry.type == "team_pending":
                pending[entry.data["id"]] = plain_data(entry.data)
        for identifier, task in pending.items():
            for native in tasks.values():
                if native.get("pending_id") == identifier:
                    task["status"] = native["status"]
                    task["native_task_id"] = native["task_id"]
            nodes[identifier] = task
        return nodes

    async def record_updates(self, updates, run_id, *, result_text=None):
        if not updates:
            return
        existing = await self.tasks()
        observed = None
        if result_text is not None:
            try:
                observed = json.loads(result_text)
            except (ValueError, TypeError):
                pass
        for identifier, native_task in updates.items():
            if identifier != native_task["task_id"] or identifier != native_task["thread_id"]:
                raise ToolError("invalid_team_task", "Framework task and thread IDs disagree")
            task = dict(existing.get(identifier, {}))
            task.update(plain_data(native_task))
            if task["status"] != "error":
                task.pop("pending", None)
            if isinstance(observed, dict) and observed.get("thread_id") == identifier:
                task.pop("pending", None)
                try:
                    failure = json.loads(observed.get("error", ""))
                except (ValueError, TypeError):
                    failure = None
                if isinstance(failure, dict) and failure.get("code") == "worker_needs_input":
                    task["pending"] = failure.get("pending", [])
            for agent in self.agents:
                if agent.name == task["agent_name"] and "service_url" not in task:
                    task["service_url"] = agent.url
                    task["graph_id"] = agent.graph_id
            if existing.get(identifier) == task:
                continue
            await self.history.append_entry(EntryDraft("team_task", data=task, run_id=run_id))

    async def add_dependencies(self, task_id, dependencies):
        async with self.lock:
            tasks = await self.tasks()
            if task_id not in tasks:
                raise ToolError("unknown_team_task", "Task does not exist")
            validate_dependencies(task_id, dependencies, tasks)
            task = dict(tasks[task_id])
            task["dependencies"] = list(dependencies)
            await self.history.append_entry(EntryDraft("team_task", data=task))

    async def reconcile(self):
        if self.commands is None:
            return
        async with self.lock:
            tasks = await self.tasks()
            for identifier, task in tasks.items():
                try:
                    updates, text = await self.commands.execute(
                        "check_async_task", {"task_id": identifier}, tasks
                    )
                except ToolError as error:
                    text = None
                    updates = {
                        identifier: {
                            **task,
                            "status": "interrupted",
                            "reconciliation_error": error.code,
                        }
                    }
                await self.record_updates(updates, None, result_text=text)
            snapshot = await self.history.snapshot()
            pending = {}
            for entry in snapshot.entries:
                if entry.type == "team_pending":
                    pending[entry.data["id"]] = plain_data(entry.data)
            for task in pending.values():
                if task["status"] == "starting":
                    task.update(status="interrupted", error="executor_lost_during_start")
                    await self.history.append_entry(EntryDraft("team_pending", data=task))

    async def execute(self, name, arguments, *, dependencies=(), pending_id=None, answering=False):
        if self.commands is None:
            raise ToolError("team_unavailable", "No Agent Protocol roles are configured")
        async with self.lock:
            await self.validate_call(
                name, arguments, dependencies=dependencies, answering=answering
            )
            tasks = await self.tasks()
            native_arguments = dict(arguments)
            worktree = None
            if name == "start_async_task":
                base = native_arguments.pop("base", None)
                include = native_arguments.pop("include_uncommitted", False)
                if (
                    base is not None
                    and (not isinstance(base, str) or not base or base.startswith("-"))
                ) or not isinstance(include, bool):
                    raise ToolError(
                        "invalid_worktree_start",
                        "Choose a Git revision and an explicit boolean for uncommitted changes",
                    )
                if base is not None or include:
                    worktree = {"base": base or "HEAD", "include_uncommitted": include}
                    native_arguments["description"] = json_dumps(
                        {
                            "pycode_task": {
                                "instruction": arguments["description"],
                                "worktree": worktree,
                            }
                        },
                        ensure_ascii=False,
                    )
            updates, text = await self.commands.execute(name, native_arguments, tasks)
            if worktree is not None:
                for update in updates.values():
                    update["worktree_start"] = worktree
            if dependencies:
                for update in updates.values():
                    update["dependencies"] = list(dependencies)
            if pending_id is not None:
                for update in updates.values():
                    update["pending_id"] = pending_id
            await self.record_updates(updates, None, result_text=text)
            await self.history.append_entry(
                EntryDraft(
                    "team_control",
                    data={"command": name, "arguments": arguments, "result": text},
                )
            )
            return text

    async def answer(self, task_id, answers):
        if not isinstance(answers, dict):
            raise ToolError("invalid_worker_answer", "Worker answers must be an object")
        await self.execute("check_async_task", {"task_id": task_id})
        task = (await self.tasks()).get(task_id)
        pending = task.get("pending", ()) if task is not None else ()
        if not pending:
            raise ToolError(
                "worker_wait_expired", "Worker has no live question; check its current status"
            )
        if len(pending) == 1 and ("operation_id" in answers or "responses" in answers):
            validate_operation_answer(pending[0], answers)
        else:
            questions = {question["interrupt_id"]: question for question in pending}
            if not answers or not set(answers) <= set(questions):
                raise ToolError(
                    "invalid_worker_answer", "Answers must identify current Worker interrupts"
                )
            for identifier, answer in answers.items():
                validate_operation_answer(questions[identifier], answer)
        return await self.execute(
            "update_async_task",
            {"task_id": task_id, "message": json_dumps({"approval": answers}, ensure_ascii=False)},
            answering=True,
        )

    async def queue(
        self, agent, description, dependencies, *, base=None, include_uncommitted=False
    ):
        await self.authorize("queue", {"subagent_type": agent})
        tasks = await self.dependency_nodes()
        identifier = uuid4().hex
        validate_dependencies(identifier, dependencies, tasks)
        await self.history.append_entry(
            EntryDraft(
                "team_pending",
                data={
                    "id": identifier,
                    "agent": agent,
                    "description": description,
                    "dependencies": list(dependencies),
                    "status": "waiting",
                    "base": base,
                    "include_uncommitted": include_uncommitted,
                },
            )
        )
        await self.schedule_ready()
        return identifier

    async def schedule_ready(self):
        async with self.scheduling:
            await self.schedule_ready_locked()

    async def schedule_ready_locked(self):
        snapshot = await self.history.snapshot()
        pending = {}
        for entry in snapshot.entries:
            if entry.type == "team_pending":
                pending[entry.data["id"]] = plain_data(entry.data)
        tasks = await self.dependency_nodes()
        for task in pending.values():
            if task["status"] != "waiting" or not dependencies_ready(task["dependencies"], tasks):
                continue
            try:
                await self.authorize("start_async_task", {"subagent_type": task["agent"]})
            except ToolError as error:
                if error.code == "plan_read_only":
                    continue
                raise
            try:
                task["status"] = "starting"
                await self.history.append_entry(EntryDraft("team_pending", data=task))
                await self.execute(
                    "start_async_task",
                    {
                        "subagent_type": task["agent"],
                        "description": task["description"],
                        "base": task.get("base"),
                        "include_uncommitted": task.get("include_uncommitted", False),
                    },
                    dependencies=task["dependencies"],
                    pending_id=task["id"],
                )
            except ToolError as error:
                if error.code == "team_concurrency_limit":
                    task["status"] = "waiting"
                    await self.history.append_entry(EntryDraft("team_pending", data=task))
                    return
                task.update(status="error", error=error.code)
                await self.history.append_entry(EntryDraft("team_pending", data=task))
                raise
            task["status"] = "started"
            await self.history.append_entry(EntryDraft("team_pending", data=task))
