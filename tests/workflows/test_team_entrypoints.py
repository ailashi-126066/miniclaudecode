"""Public Team controls use the configured session, native API and a real HTTP host."""

import asyncio
import json
import socket
from contextlib import asynccontextmanager

import pytest
import uvicorn
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import (
    build_tui,
    build_worker_host,
    issue_worker_host_token,
    open_agent_session,
)
from pycode.core.errors import ToolError
from pycode.core.messages import Message


@asynccontextmanager
async def worker_server(config, providers):
    protocol, worktrees = build_worker_host(config, providers)
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.setblocking(False)
    port = listener.getsockname()[1]
    # Clients find the token through the user data directory, exactly as with `team serve`.
    token = issue_worker_host_token(config, port)
    host = uvicorn.Server(uvicorn.Config(protocol.application(token), log_level="error", ws="none"))
    running = asyncio.create_task(host.serve(sockets=[listener]))
    try:
        async with asyncio.timeout(10):
            while not host.started:
                if running.done():
                    await running
                await asyncio.sleep(0.01)
        yield "http://127.0.0.1:" + str(port), protocol, worktrees
    finally:
        host.should_exit = True
        await asyncio.wait_for(running, 10)
        listener.close()


async def configured_session(tmp_path, model_url, service_url):
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    config.user_data.mkdir(exist_ok=True)
    (config.user_data / "team.json").write_text(
        json.dumps(
            [
                {
                    "name": "integration",
                    "description": "Explicit integration Worker",
                    "graph_id": "integration",
                    "url": service_url,
                    "modifies_workspace": True,
                }
            ]
        ),
        encoding="utf-8",
    )
    session = await open_agent_session(
        config, (provider(model_url),), "lead", create=True, refresh_models=False
    )
    return session


async def test_tui_forwards_worker_permission_to_the_same_native_thread(tmp_path, monkeypatch):
    from tests.adapters.ui.test_tui_http import settled
    from textual.widgets import Static

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    replies = [
        chat_completion(),
        skill_reply("Bash", {"command": "printf once > worker-effect.txt"}, "worker-bash"),
        chat_completion(),
    ]
    with protocol_server(replies) as (model_url, requests):
        config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
        async with worker_server(config, (provider(model_url),)) as (service_url, protocol, _):
            config.user_data.mkdir(exist_ok=True)
            (config.user_data / "team.json").write_text(
                json.dumps(
                    [
                        {
                            "name": "integration",
                            "description": "Explicit integration Worker",
                            "graph_id": "integration",
                            "url": service_url,
                            "modifies_workspace": True,
                        }
                    ]
                ),
                encoding="utf-8",
            )
            app = build_tui(config, (provider(model_url),))
            async with app.run_test(size=(100, 40)) as pilot:
                await app.ready.wait()
                await app.send_text("Prepare a team")
                await settled(app)
                await app.session.team_control(
                    "start_async_task",
                    {"subagent_type": "integration", "description": "Run a command"},
                )
                identifier = next(iter((await app.session.inspect())["team"]))
                await asyncio.wait_for(protocol.running[identifier], 10)
                await app.command("/team check " + identifier)
                await settled(app)
                await pilot.pause()
                assert app.focused.id == "perm-inline"
                assert identifier in str(app.query_one("#approval-details Static", Static).render())
                await pilot.press("enter")
                await settled(app)
                await asyncio.wait_for(protocol.running[identifier], 10)
                assert (tmp_path / "worker-effect.txt").read_text() == "once"
                await app.command("/team check " + identifier)
                await settled(app)
                assert not app.query("#perm-inline")
                assert len(requests) == 3


@pytest.mark.parametrize("include", [False, True])
async def test_native_start_carries_explicit_worktree_base_and_uncommitted_choice(
    tmp_path, monkeypatch, include
):
    from pathlib import Path

    from tests.adapters.snapshot.test_git_tree import git, repository

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    project = repository(tmp_path)
    source = project / "code.py"
    source.write_text("initial\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "fixture initial")
    base = git(project, "rev-parse", "HEAD").decode().strip()
    source.write_text("current committed\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "fixture current")
    source.write_text("uncommitted\n")
    with protocol_server([chat_completion(), chat_completion()]) as (model_url, requests):
        config = AppConfig(project, tmp_path / "data", memory_mode="off")
        async with worker_server(config, (provider(model_url),)) as (
            service_url,
            protocol,
            worktrees,
        ):
            config.user_data.mkdir(exist_ok=True)
            (config.user_data / "team.json").write_text(
                json.dumps(
                    [
                        {
                            "name": "worker",
                            "description": "Modifying Worker",
                            "graph_id": "worker",
                            "url": service_url,
                            "modifies_workspace": True,
                        }
                    ]
                ),
                encoding="utf-8",
            )
            session = await open_agent_session(
                config, (provider(model_url),), "lead", create=True, refresh_models=False
            )
            try:
                await session.run(Message("u1", "user", "Prepare a team"))
                await session.team_control(
                    "start_async_task",
                    {
                        "subagent_type": "worker",
                        "description": "Inspect only",
                        "base": base,
                        "include_uncommitted": include,
                    },
                )
                identifier = next(iter((await session.inspect())["team"]))
                await asyncio.wait_for(protocol.running[identifier], 10)
                worker = protocol.threads[identifier].worker
                record = await worktrees.inspect(worker.worktree_id)
                assert record["base"] == base
                assert (Path(record["path"]) / "code.py").read_text() == (
                    "uncommitted\n" if include else "initial\n"
                )
                assert bool(record.get("starting_snapshot")) is include
                assert source.read_text() == "uncommitted\n"
                assert len(requests) == 2
            finally:
                await session.aclose()


@pytest.mark.parametrize("action", ["answer", "cancel_then_update"])
async def test_worker_approval_is_visible_answered_or_cancelled_through_public_controls(
    tmp_path, monkeypatch, action
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    replies = [
        chat_completion(),
        skill_reply("Bash", {"command": "printf approved > worker-effect.txt"}, "worker-bash"),
        chat_completion(),
    ]
    with protocol_server(replies) as (model_url, requests):
        config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
        async with worker_server(config, (provider(model_url),)) as (service_url, protocol, _):
            session = await configured_session(tmp_path, model_url, service_url)
            try:
                assert (
                    await session.run(Message("u1", "user", "Prepare a team"))
                ).outcome == "completed"
                await session.team_control(
                    "start_async_task",
                    {"subagent_type": "integration", "description": "Run the approved command"},
                )
                identifier = next(iter((await session.inspect())["team"]))
                await asyncio.wait_for(protocol.running[identifier], 10)
                await session.team_control("check_async_task", {"task_id": identifier})
                task = (await session.inspect())["team"][identifier]
                assert task["pending"][0]["kind"] == "permission"
                assert not (tmp_path / "worker-effect.txt").exists()
                before_run = task["run_id"]
                if action == "answer":
                    await session.team_answer(
                        identifier,
                        {"operation_id": task["pending"][0]["operation_id"], "decision": "approve"},
                    )
                    await asyncio.wait_for(protocol.running[identifier], 10)
                    assert (tmp_path / "worker-effect.txt").read_text() == "approved"
                    with pytest.raises(ToolError, match="no live question"):
                        await session.team_answer(
                            identifier,
                            {
                                "operation_id": task["pending"][0]["operation_id"],
                                "decision": "approve",
                            },
                        )
                else:
                    await session.team_control("cancel_async_task", {"task_id": identifier})
                    await session.team_control(
                        "update_async_task",
                        {"task_id": identifier, "message": "Inspect the current state instead"},
                    )
                    await asyncio.wait_for(protocol.running[identifier], 10)
                    assert not (tmp_path / "worker-effect.txt").exists()
                await session.team_control("check_async_task", {"task_id": identifier})
                after = (await session.inspect())["team"][identifier]
                assert after["thread_id"] == identifier and after["run_id"] != before_run
                assert after["status"] == "success" and not after.get("pending")
                assert len(requests) == 3
            finally:
                await session.aclose()
