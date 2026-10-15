"""Dependency scheduling and durable native task references have one execution owner."""

import asyncio

import pytest
from tests.adapters.test_team_protocol import AUTHORIZATION, server
from tests.services.test_model_control import store_for

from pycode.core.errors import ToolError
from pycode.core.messages import Message
from pycode.core.team import TeamAgent
from pycode.services.team import TeamManager
from pycode.workflows.team import NativeTeamCommands


def manager(store, url):
    role = TeamAgent("worker", "fixed Worker", "worker", url)
    commands = NativeTeamCommands(
        [
            {
                "name": role.name,
                "description": role.description,
                "graph_id": role.graph_id,
                "url": role.url,
                "headers": AUTHORIZATION,
            }
        ]
    )
    return TeamManager(store, (role,), commands=commands)


async def test_concurrency_dependencies_query_update_and_single_start_after_restart(tmp_path):
    store = store_for(tmp_path)
    await store.append_message(Message("u1", "user", "team"))
    async with server() as (url, protocol, workers):
        team = manager(store, url)
        try:
            for _ in range(4):
                await team.execute(
                    "start_async_task", {"subagent_type": "worker", "description": "wait"}
                )
            tasks = await team.tasks()
            assert len(tasks) == 4 and len(workers) == 4
            with pytest.raises(ToolError) as error:
                await team.execute(
                    "start_async_task", {"subagent_type": "worker", "description": "finish"}
                )
            assert error.value.code == "team_concurrency_limit" and len(workers) == 4
            predecessor = next(iter(tasks))
            pending_id = await team.queue("worker", "finish", (predecessor,))
            assert len(workers) == 4
            await team.execute("update_async_task", {"task_id": predecessor, "message": "finish"})
            async with asyncio.timeout(10):
                while (await team.tasks())[predecessor]["status"] != "success":
                    await team.execute("check_async_task", {"task_id": predecessor})
                    await asyncio.sleep(0.01)
            await team.schedule_ready()
            assert len(workers) == 5
            pending = [
                entry
                for entry in (await store.snapshot()).entries
                if entry.type == "team_pending" and entry.data["id"] == pending_id
            ]
            assert pending[-1].data["status"] == "started"
            restarted = manager(store, url)
            await restarted.reconcile()
            await restarted.schedule_ready()
            assert len(workers) == 5
            first = (await restarted.tasks())[predecessor]
            assert first["task_id"] == first["thread_id"] and first["status"] == "success"
            assert first["service_url"] == url
            with pytest.raises(ToolError, match="acyclic"):
                await restarted.add_dependencies(predecessor, (predecessor,))
        finally:
            await store.aclose()


async def test_unreachable_executor_marked_interrupted_without_automatic_run(tmp_path):
    store = store_for(tmp_path)
    await store.append_message(Message("u1", "user", "team"))
    async with server() as (url, _, _):
        team = manager(store, url)
        await team.execute("start_async_task", {"subagent_type": "worker", "description": "wait"})
        task_id = next(iter(await team.tasks()))
    try:
        restarted = manager(store, url)
        await restarted.reconcile()
        task = (await restarted.tasks())[task_id]
        assert task["status"] == "interrupted"
        assert task["reconciliation_error"] == "team_service_unavailable"
        assert task["task_id"] == task_id
    finally:
        await store.aclose()
