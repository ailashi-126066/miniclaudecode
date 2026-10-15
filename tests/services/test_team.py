"""Team task storage, dependency validation and scheduling stay usable without a remote host."""

import pytest
from tests.services.test_model_control import store_for

from pycode.core.errors import ToolError
from pycode.core.messages import Message
from pycode.core.team import TeamAgent
from pycode.services.team import TeamManager


class FakeCommands:
    """In-process stand-in for the task command port; no network service is involved."""

    def __init__(self):
        self.started = []
        self.status = {}

    async def execute(self, name, arguments, tasks):
        if name == "start_async_task":
            identifier = "task-" + str(len(self.started))
            self.started.append(arguments["description"])
            self.status[identifier] = "running"
        else:
            identifier = arguments["task_id"]
        task = {
            "task_id": identifier,
            "thread_id": identifier,
            "agent_name": "reader",
            "status": self.status[identifier],
        }
        return {identifier: task}, identifier


def manager(store, commands, concurrency=2):
    role = TeamAgent("reader", "Read-only reviewer", "readonly", "local")
    return TeamManager(store, (role,), concurrency=concurrency, commands=commands)


async def test_concurrency_dependencies_and_cycle_validation(tmp_path):
    store = store_for(tmp_path)
    await store.append_message(Message("u1", "user", "team"))
    commands = FakeCommands()
    team = manager(store, commands)
    try:
        for _ in range(2):
            await team.execute("start_async_task", {"subagent_type": "reader", "description": "a"})
        with pytest.raises(ToolError) as error:
            await team.execute("start_async_task", {"subagent_type": "reader", "description": "b"})
        assert error.value.code == "team_concurrency_limit"
        first = next(iter(await team.tasks()))
        pending_id = await team.queue("reader", "after first", (first,))
        assert commands.started == ["a", "a"]
        commands.status[first] = "success"
        await team.execute("check_async_task", {"task_id": first})
        await team.schedule_ready()
        assert commands.started == ["a", "a", "after first"]
        pending = [
            entry
            for entry in (await store.snapshot()).entries
            if entry.type == "team_pending" and entry.data["id"] == pending_id
        ]
        assert pending[-1].data["status"] == "started"
        with pytest.raises(ToolError, match="acyclic"):
            await team.add_dependencies(first, (first,))
        with pytest.raises(ToolError) as error:
            await team.queue("reader", "missing", ("unknown",))
        assert error.value.code == "unknown_dependency"
    finally:
        await store.aclose()


async def test_execution_requires_a_command_port(tmp_path):
    store = store_for(tmp_path)
    try:
        with pytest.raises(ToolError) as error:
            await manager(store, None).execute("list_async_tasks", {})
        assert error.value.code == "team_unavailable"
    finally:
        await store.aclose()
