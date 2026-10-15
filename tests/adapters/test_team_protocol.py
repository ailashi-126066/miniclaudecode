"""Native framework Async Subagents tools against a real local HTTP host."""

import asyncio
import json
import socket
from contextlib import asynccontextmanager
from uuid import uuid4

import httpx
import pytest
import uvicorn
from deepagents.middleware.async_subagents import AsyncSubAgentMiddleware
from langchain.tools import ToolRuntime
from langchain_core.messages import AIMessage
from tests.workflows.test_agent import assemble, tool_response

from pycode.adapters.team.protocol import LocalAgentProtocol
from pycode.core.messages import Message


class FixedWorker:
    def __init__(self):
        self.runtime, self.context, self.model, _, self.journal, _ = assemble(
            [
                tool_response(),
                AIMessage(id="worker-finish", content="worker completed"),
            ]
        )
        self.cancelled = False
        self.instructions = []

    async def run(self, instruction):
        self.instructions.append(instruction)
        self.context.run_id = uuid4().hex
        self.model.release = asyncio.Event() if instruction == "wait" else None
        history = tuple(self.journal.messages)
        try:
            return await self.runtime.run(
                (Message(uuid4().hex, "user", instruction),), self.context, history=history
            )
        except asyncio.CancelledError:
            self.cancelled = True
            raise

    async def values(self):
        messages = []
        for message in self.journal.messages:
            if message.role == "assistant":
                messages.append({"role": "assistant", "content": message.text})
        return {"messages": messages}

    async def aclose(self):
        return None


@asynccontextmanager
async def server(initial_role="worker"):
    workers = []

    async def factory(thread_id, graph_id):
        if graph_id != initial_role:
            raise ValueError("Unknown role")
        worker = FixedWorker()
        workers.append(worker)
        return worker

    protocol = LocalAgentProtocol(factory)
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.setblocking(False)
    url = "http://127.0.0.1:" + str(listener.getsockname()[1])
    host = uvicorn.Server(uvicorn.Config(protocol.application(), log_level="error", ws="none"))
    running = asyncio.create_task(host.serve(sockets=[listener]))
    try:
        async with asyncio.timeout(10):
            while not host.started:
                if running.done():
                    await running
                await asyncio.sleep(0.01)
        yield url, protocol, workers
    finally:
        host.should_exit = True
        await asyncio.wait_for(running, 10)
        listener.close()


def tools_for(url, graph_id="worker"):
    middleware = AsyncSubAgentMiddleware(
        async_subagents=[
            {
                "name": "worker",
                "description": "fixed Worker",
                "graph_id": graph_id,
                "url": url,
            }
        ]
    )
    tools = {}
    for tool in middleware.tools:
        tools[tool.name] = tool
    return tools


async def native_call(tools, state, name, **arguments):
    runtime = ToolRuntime(state, None, {}, lambda value: None, uuid4().hex, None)
    result = await tools[name].coroutine(runtime=runtime, **arguments)
    assert isinstance(result.update, dict), result
    state.setdefault("async_tasks", {}).update(result.update.get("async_tasks", {}))
    return result.update["messages"][0].content


async def test_native_start_check_update_cancel_list_and_same_thread_history():
    async with server() as (url, protocol, workers):
        tools = tools_for(url)
        state = {}
        await native_call(
            tools, state, "start_async_task", description="wait", subagent_type="worker"
        )
        task_id = next(iter(state["async_tasks"]))
        original = dict(state["async_tasks"][task_id])
        await workers[0].model.started.wait()
        await native_call(tools, state, "update_async_task", task_id=task_id, message="finish")
        updated = state["async_tasks"][task_id]
        assert updated["thread_id"] == original["thread_id"] == task_id
        assert updated["run_id"] != original["run_id"]
        assert protocol.runs[original["run_id"]]["status"] == "cancelled"
        assert workers[0].cancelled
        async with asyncio.timeout(10):
            while state["async_tasks"][task_id]["status"] != "success":
                content = await native_call(tools, state, "check_async_task", task_id=task_id)
                await asyncio.sleep(0.01)
        assert "worker completed" in content
        assert workers[0].model.call_index == 2
        assert workers[0].instructions == ["wait", "finish"]
        assert [
            message.text for message in workers[0].journal.messages if message.role == "user"
        ] == [
            "wait",
            "finish",
        ]
        listed = await native_call(tools, state, "list_async_tasks", status_filter="all")
        assert task_id in listed
        await native_call(
            tools, state, "start_async_task", description="wait", subagent_type="worker"
        )
        new_id = next(identifier for identifier in state["async_tasks"] if identifier != task_id)
        assert new_id != task_id
        await workers[1].model.started.wait()
        await native_call(tools, state, "cancel_async_task", task_id=new_id)
        assert workers[1].cancelled
        assert state["async_tasks"][new_id]["status"] == "cancelled"


@pytest.mark.parametrize("count", [1, 3])
async def test_server_close_cancels_and_collects_every_owned_worker(count):
    async with server() as (url, protocol, workers):
        tools = tools_for(url)
        state = {}
        for _ in range(count):
            await native_call(
                tools, state, "start_async_task", description="wait", subagent_type="worker"
            )
        for worker in workers:
            await worker.model.started.wait()
    assert all(worker.cancelled for worker in workers)
    assert all(task.done() for task in protocol.running.values())


@pytest.mark.parametrize("change", ["readonly", "integration", "workspace"])
@pytest.mark.parametrize("initial_role", ["readonly", "worker", "integration"])
async def test_existing_worker_rejects_role_or_workspace_change_before_interrupt(
    change, initial_role
):
    async with server(initial_role) as (url, protocol, workers):
        state = {}
        await native_call(
            tools_for(url, initial_role),
            state,
            "start_async_task",
            description="wait",
            subagent_type="worker",
        )
        task_id = next(iter(state["async_tasks"]))
        run_id = state["async_tasks"][task_id]["run_id"]
        await asyncio.wait_for(workers[0].model.started.wait(), 10)
        instruction = "change role"
        role = change
        if role == initial_role:
            role = "worker" if initial_role != "worker" else "readonly"
        if change == "workspace":
            role = initial_role
            instruction = json.dumps(
                {
                    "pycode_task": {
                        "instruction": "change workspace",
                        "worktree": {"base": "HEAD", "include_uncommitted": False},
                    }
                }
            )
        async with httpx.AsyncClient(base_url=url, timeout=5) as client:
            response = await client.post(
                f"/threads/{task_id}/runs",
                json={
                    "assistant_id": role,
                    "input": {"messages": [{"role": "user", "content": instruction}]},
                    "multitask_strategy": "interrupt",
                },
            )
        assert response.status_code == 409
        assert len(workers) == len(protocol.runs) == 1
        assert protocol.threads[task_id].graph_id == initial_role
        assert protocol.runs[run_id]["status"] == "running"
        assert workers[0].instructions == ["wait"] and not workers[0].cancelled
