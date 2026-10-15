"""Atomic task board policies over the team's single JSONL transition writer."""

import asyncio

from pycode.kernel import ToolError
from pycode.team.domain.team import TASK_STATES, bounded_text, claim_error, validate_dependencies


class TeamManager:
    def __init__(self, commit):
        self._commit = commit
        self.records = {}
        self.lock = asyncio.Lock()

    async def tasks(self):
        return {key: dict(value) for key, value in self.records.items()}

    async def create_task(self, title, description="", depends_on=()):
        async with self.lock:
            if len(self.records) >= 200:
                raise ToolError("task_limit_reached", "Task board is full")
            identifier = "t" + str(max((int(key[1:]) for key in self.records), default=0) + 1)
            validate_dependencies(identifier, depends_on, self.records)
            task = {
                "task_id": identifier,
                "title": bounded_text(title, "title", 200),
                "description": bounded_text(description, "description", 4000, empty=True),
                "dependencies": list(depends_on),
                "status": "open",
                "assignee": None,
                "result": "",
                "done_by": None,
                "last_assignee": None,
                "interrupted": False,
            }
            await self._commit(tasks=[task])
            return task

    def claimed(self, task_id, agent_id):
        error = claim_error(task_id, agent_id, self.records)
        if error:
            raise ToolError(error, "Cannot claim task: " + error)
        return {**self.records[task_id], "status": "in_progress", "assignee": agent_id}

    async def claim_task(self, task_id, agent_id):
        async with self.lock:
            task = self.claimed(task_id, agent_id)
            await self._commit(tasks=[task])
            return task

    async def add_dependencies(self, task_id, dependencies):
        return await self.update_task(task_id, actor="lead", depends_on=dependencies)

    async def update_task(self, task_id, *, actor, **updates):
        async with self.lock:
            if task_id not in self.records:
                raise ToolError("unknown_task", "Task does not exist")
            task = dict(self.records[task_id])
            if actor != "lead":
                if task.get("assignee") != actor:
                    raise ToolError("task_not_owned", "Only the owner can update this task")
                if set(updates) - {"status", "result"} or updates.get("status") not in {
                    "in_progress",
                    "done",
                    "failed",
                }:
                    raise ToolError("invalid_task_update", "Child can only update status/result")
            for field, limit in (("title", 200), ("description", 4000), ("result", 4000)):
                if field in updates:
                    task[field] = bounded_text(updates[field], field, limit, empty=field != "title")
            if "depends_on" in updates:
                validate_dependencies(task_id, updates["depends_on"], self.records)
                task["dependencies"] = list(updates["depends_on"])
            if "status" in updates:
                if updates["status"] not in TASK_STATES:
                    raise ToolError("invalid_task_status", "Unknown task status")
                task["status"] = updates["status"]
                if task["status"] == "done":
                    task["done_by"] = task.get("assignee") or actor
                if task["status"] == "open":
                    task.update(assignee=None, done_by=None)
            await self._commit(tasks=[task])
            return task

    def settled(self, agent_id, outcome, text):
        tasks = []
        for original in self.records.values():
            if original.get("assignee") != agent_id or original["status"] != "in_progress":
                continue
            task = dict(original)
            if outcome == "completed":
                task.update(status="done", result=text[:4000], done_by=agent_id)
            elif outcome == "failed":
                task.update(status="failed", result=text[:4000])
            else:
                task.update(status="open", assignee=None, last_assignee=agent_id, interrupted=True)
            tasks.append(task)
        return tasks
