"""Actual LangGraph checkpoint continuation transfers a live task from A to B."""

import asyncio
import threading

from pycode.app.bootstrap import compose_application
from pycode.app.configuration.settings import AppConfig
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.team.services.test_handoff import eventually


async def test_new_member_continues_same_task_checkpoint_without_repeating_input(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    held = chat_completion()
    held.started, held.release = threading.Event(), threading.Event()
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([held, chat_completion()]) as (url, requests):
        selected = provider(url)
        lead = await compose_application(config, (selected,)).open_session(
            "lead", create=True, refresh_models=False
        )
        try:
            team = lead._team
            record = await team.spawn("readonly", "reviewer", "Inspect task", selected)
            assert await asyncio.to_thread(held.started.wait, 5)
            a = record["agent_id"]
            child = team.sessions[a]
            old = child._launcher._active_container
            checkpointer = old.runtime.checkpointer
            run_id = old.context.run_id
            result = await team.handoff_task(record["task_id"])
            b = result["agent_id"]
            assert b != a
            assert team.sessions[b] is child
            assert team.records[b]["session_id"] == record["session_id"]
            await eventually(lambda: team.records[b]["status"] in {"completed", "failed"})
            assert team.records[b]["status"] == "completed", team.records[b]
            snapshot = await child.task_evidence()
            starts = [e for e in snapshot.entries if e.data.get("kind") == "run_start"]
            ends = [e for e in snapshot.entries if e.data.get("kind") == "run_end"]
            assert len(starts) == len(ends) == 1
            assert starts[0].run_id == run_id == ends[0].run_id
            inputs = [
                e for e in snapshot.entries if e.message and e.message.id == "team-start-" + a
            ]
            assert len(inputs) == 1
            assert team.board.records[record["task_id"]]["status"] == "submitted"
            assert len(requests) == 2
            saved = await checkpointer.aget_tuple(
                {"configurable": {"thread_id": f"{record['session_id']}:{run_id}"}}
            )
            assert saved is not None
        finally:
            held.release.set()
            await lead.aclose()


async def test_handoff_after_tool_keeps_disclosure_and_does_not_repeat_tool(tmp_path, monkeypatch):
    from tests.skills.adapters.test_skills_http import skill_reply

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    (tmp_path / "code.py").write_text("unique-task-code\n")
    held = chat_completion()
    held.started, held.release = threading.Event(), threading.Event()
    replies = [
        skill_reply("ToolSearch", {"query": "read_file"}, "search"),
        skill_reply("read_file", {"file_path": "/workspace/code.py"}, "read"),
        held,
        chat_completion(),
    ]
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server(replies) as (url, requests):
        selected = provider(url)
        lead = await compose_application(config, (selected,)).open_session(
            "lead", create=True, refresh_models=False
        )
        try:
            team = lead._team
            a = await team.spawn("readonly", "reader", "Read code.py", selected)
            assert await asyncio.to_thread(held.started.wait, 5)
            transfer = await team.handoff_task(a["task_id"])
            b = transfer["agent_id"]
            await eventually(lambda: team.records[b]["status"] in {"completed", "failed"})
            assert team.records[b]["status"] == "completed", team.records[b]
            snapshot = await team.sessions[b].task_evidence()
            results = [
                e.message.tool_result
                for e in snapshot.entries
                if e.message and e.message.tool_result
            ]
            assert [result.name for result in results] == ["ToolSearch", "read_file"]
            assert "unique-task-code" in str(requests[-1]["body"]["messages"])
            assert "read_file" in str(requests[-1]["body"]["tools"])
            assert len(requests) == 4
        finally:
            held.release.set()
            await lead.aclose()


async def test_cancelled_write_is_recorded_and_not_replayed_by_replacement(tmp_path, monkeypatch):
    from pycode.tools.adapters.backend import PycodeBackend
    from pycode.tools.adapters.permissions import InterruptToolGate
    from tests.skills.adapters.test_skills_http import skill_reply
    from tests.snapshot.adapters.test_git_tree import git, repository

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    workspace = repository(tmp_path)
    (workspace / "base.py").write_text("base\n")
    git(workspace, "add", ".")
    git(workspace, "commit", "-qm", "base")
    started = asyncio.Event()
    writes = []
    original = PycodeBackend.awrite

    async def write_then_wait(self, path, content):
        result = await original(self, path, content)
        writes.append(path)
        started.set()
        await asyncio.Event().wait()
        return result

    monkeypatch.setattr(PycodeBackend, "awrite", write_then_wait)
    replies = [
        skill_reply("ToolSearch", {"query": "write_file"}, "search"),
        skill_reply(
            "write_file", {"file_path": "/workspace/new.py", "content": "changed\n"}, "write"
        ),
        chat_completion(),
    ]
    with protocol_server(replies) as (url, _):
        selected = provider(url)
        lead = await compose_application(
            AppConfig(
                workspace, tmp_path / "data", memory_mode="off", tool_gate=InterruptToolGate()
            ),
            (selected,),
        ).open_session("lead", create=True, refresh_models=False)
        try:
            team = lead._team
            a = await team.spawn("worker", "writer", "Write new.py", selected)
            await eventually(lambda: team.records[a["agent_id"]]["status"] == "needs_approval")
            pending = team.records[a["agent_id"]]["pending"][0]
            await team.answer(
                a["agent_id"], {"operation_id": pending["operation_id"], "decision": "approve"}
            )
            async with asyncio.timeout(5):
                await started.wait()
            transfer = await team.handoff_task(a["task_id"])
            b = transfer["agent_id"]
            await eventually(lambda: team.records[b]["status"] in {"completed", "failed"})
            assert team.records[b]["status"] == "completed", team.records[b]
            assert writes == ["/new.py"]
            assert team.records[b]["worktree_id"] == a["worktree_id"]
            snapshot = await team.sessions[b].task_evidence()
            results = [
                e.message.tool_result
                for e in snapshot.entries
                if e.message
                and e.message.tool_result
                and e.message.tool_result.name == "write_file"
            ]
            assert len(results) == 1 and results[0].error_code == "tool_interrupted"
        finally:
            await lead.aclose()


async def test_handoff_keeps_file_read_version_for_later_edit(tmp_path, monkeypatch):
    from pathlib import Path

    from tests.skills.adapters.test_skills_http import skill_reply
    from tests.snapshot.adapters.test_git_tree import git, repository

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    workspace = repository(tmp_path)
    (workspace / "base.py").write_text("value = 1\n")
    git(workspace, "add", ".")
    git(workspace, "commit", "-qm", "base")
    held = chat_completion()
    held.started, held.release = threading.Event(), threading.Event()
    replies = [
        skill_reply("ToolSearch", {"query": "read_file edit_file", "limit": 2}, "search"),
        skill_reply("read_file", {"file_path": "/workspace/base.py"}, "read"),
        held,
        skill_reply(
            "edit_file",
            {
                "file_path": "/workspace/base.py",
                "old_string": "value = 1",
                "new_string": "value = 2",
            },
            "edit",
        ),
        chat_completion(),
    ]
    with protocol_server(replies) as (url, _):
        selected = provider(url)
        lead = await compose_application(
            AppConfig(workspace, tmp_path / "data", memory_mode="off"), (selected,)
        ).open_session("lead", create=True, refresh_models=False)
        try:
            team = lead._team
            a = await team.spawn("worker", "editor", "Read then edit base.py", selected)
            assert await asyncio.to_thread(held.started.wait, 5)
            transfer = await team.handoff_task(a["task_id"])
            b = transfer["agent_id"]
            await eventually(lambda: team.records[b]["status"] in {"completed", "failed"})
            assert team.records[b]["status"] == "completed", team.records[b]
            assert (Path(team.records[b]["workspace"]) / "base.py").read_text() == "value = 2\n"
            assert (workspace / "base.py").read_text() == "value = 1\n"
        finally:
            held.release.set()
            await lead.aclose()


async def test_real_idle_monitor_replaces_stalled_model_without_lead_model_decision(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    held = chat_completion()
    held.started, held.release = threading.Event(), threading.Event()
    config = AppConfig(
        tmp_path, tmp_path / "data", memory_mode="off", team_idle_timeout=0.3, team_stop_timeout=1
    )
    with protocol_server([held, chat_completion()]) as (url, requests):
        selected = provider(url)
        lead = await compose_application(config, (selected,)).open_session(
            "lead", create=True, refresh_models=False
        )
        try:
            team = lead._team
            a = await team.spawn("readonly", "reviewer", "Inspect", selected)
            assert await asyncio.to_thread(held.started.wait, 5)
            await eventually(lambda: team.records[a["agent_id"]]["status"] == "handed_off")
            b = team.records[a["agent_id"]]["replaced_by"]
            await eventually(lambda: team.records[b]["status"] in {"completed", "failed"})
            assert team.records[b]["status"] == "completed"
            assert len(requests) == 2  # No additional lead/supervisor model request.
            assert team.board.records[a["task_id"]]["assignee"] == b
        finally:
            held.release.set()
            await lead.aclose()
