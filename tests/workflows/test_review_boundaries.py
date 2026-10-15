"""Regression behavior found by the independent final review, exercised through real boundaries."""

import asyncio
from dataclasses import replace

import httpx
from langchain_core.messages import AIMessage
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_agent import Recorder, assemble
from tests.workflows.test_questions import question_response
from tests.workflows.test_skills_http import skill_reply

from pycode.adapters.team.protocol import LocalAgentProtocol
from pycode.adapters.tools.backend import PycodeBackend
from pycode.app.config import AppConfig
from pycode.app.container import build_tui, open_agent_session
from pycode.app.team_runtime import SessionWorker
from pycode.core.errors import ToolError
from pycode.core.messages import Message


async def test_first_parallel_failure_batch_must_block_fourth_execution():
    replies = [
        AIMessage(
            id="batch",
            content="",
            tool_calls=[
                {"id": str(i), "name": "echo", "args": {"value": "same"}} for i in range(5)
            ],
        ),
        AIMessage(id="final", content="done"),
    ]
    runtime, context, _, tool, journal, _ = assemble(replies)

    class Failure:
        spec = replace(tool.spec, execution_mode="sequential")
        count = 0

        async def execute(self, current, arguments):
            self.count += 1
            raise ToolError("stable_failure", "bad input")

    failing = Failure()
    context.registry.replace_source("builtin", (failing,))
    await asyncio.wait_for(runtime.run((Message("u", "user", "work"),), context), 10)
    print("actual_executions", failing.count)
    assert failing.count == 3


async def test_dialogue_revert_must_restore_plan_policy(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([chat_completion()]) as (url, _):
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (provider(url),),
            "s",
            create=True,
            refresh_models=False,
        )
        try:
            await session.run(Message("u", "user", "base"))
            before = (await session.inspect())["snapshot"]["leaf_id"]
            await session.enter_plan()
            plan = await session.preview_revert(before, "dialogue")
            await session.revert(plan)
            view = await session.inspect()
            print("plan_after_revert", view["plan"])
            assert view["plan"] is None
        finally:
            await session.aclose()


async def test_native_update_after_worker_wait_must_accept_new_instruction(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    args = {"questions": [{"type": "text", "name": "q", "message": "Answer?"}]}
    with protocol_server([question_response(args), chat_completion()]) as (url, requests):

        async def factory(identifier, graph):
            session = await open_agent_session(
                AppConfig(tmp_path, tmp_path / "data", memory_mode="off", allow_delegation=False),
                (provider(url),),
                "worker-" + identifier,
                create=True,
                refresh_models=False,
            )
            return SessionWorker(session)

        host = LocalAgentProtocol(factory)
        try:
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=host.application("audit-token")),
                base_url="http://127.0.0.1",
                headers={"Authorization": "Bearer audit-token"},
            ) as client:
                thread = (await client.post("/threads", json={})).json()["thread_id"]
                first = (
                    await client.post(
                        f"/threads/{thread}/runs",
                        json={
                            "assistant_id": "worker",
                            "input": {"messages": [{"role": "user", "content": "Ask me"}]},
                        },
                    )
                ).json()
                await host.running[thread]
                second = (
                    await client.post(
                        f"/threads/{thread}/runs",
                        json={
                            "assistant_id": "worker",
                            "multitask_strategy": "interrupt",
                            "input": {
                                "messages": [
                                    {
                                        "role": "user",
                                        "content": "Cancel that question and inspect code instead",
                                    }
                                ]
                            },
                        },
                    )
                ).json()
                await host.running[thread]
                state = (await client.get(f"/threads/{thread}/runs/{second['run_id']}")).json()
                print(
                    "first",
                    host.runs[first["run_id"]],
                    "updated",
                    state,
                    "model_requests",
                    len(requests),
                )
                assert state["status"] == "success"
        finally:
            await host.aclose()


def test_file_deleted_since_read_must_conflict(tmp_path):
    path = tmp_path / "data.txt"
    path.write_text("original")
    backend = PycodeBackend(tmp_path)
    assert backend.read("/data.txt").error is None
    path.unlink()
    result = backend.write("/data.txt", "replacement")
    print("write_error", result.error, "recreated", path.exists())
    assert result.error is not None


def test_windows_reserved_state_directory_casing_must_be_blocked(tmp_path):
    backend = PycodeBackend(tmp_path)
    result = backend.write("/.PYCODE/facts.txt", "state boundary bypass")
    print(
        "uppercase_internal_error",
        result.error,
        "created_internal",
        (tmp_path / ".pycode/facts.txt").exists(),
    )
    assert result.error is not None


async def test_bash_must_emit_output_progress_before_completion(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    events = Recorder()
    command = "printf 'first\\n'; sleep 0.8; printf 'last\\n'"
    with protocol_server(
        [skill_reply("Bash", {"command": command}, "bash"), chat_completion()]
    ) as (url, _):
        session = await open_agent_session(
            AppConfig(tmp_path, tmp_path / "data", memory_mode="off"),
            (provider(url),),
            "s",
            create=True,
            refresh_models=False,
            consumers=(events,),
        )
        try:
            waiting = await session.run(Message("u", "user", "run command"))
            question = waiting.pending[0]
            finished = await session.resume(
                {"operation_id": question["operation_id"], "decision": "approve"}
            )
            assert finished.outcome == "completed"
            updates = [e for e in events.events if e.kind == "tool_execution_update"]
            print("bash_progress_event_count", len(updates))
            assert updates
        finally:
            await session.aclose()


async def test_actual_tui_renderer_failure_must_not_close_app(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    (tmp_path / "code.txt").write_text("readable")
    with protocol_server(
        [skill_reply("read_file", {"file_path": "/workspace/code.txt"}, "read"), chat_completion()]
    ) as (url, _):
        app = build_tui(AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), (provider(url),))
        original = type(app).render_history

        async def faulted_render(current):
            if current.session is not None:
                view = await current.session.inspect()
                if any(
                    item.get("message", {}).get("tool_result")
                    for item in view["history"]
                    if item["kind"] == "message"
                ):
                    raise RuntimeError("audit: optional tool-card renderer failed")
            await original(current)

        monkeypatch.setattr(type(app), "render_history", faulted_render)
        async with app.run_test() as pilot:
            await app.ready.wait()
            await app.send_text("Read code.txt")
            await app.wait_idle()
            await pilot.pause()
            assert not app._closed
