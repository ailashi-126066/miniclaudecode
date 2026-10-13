"""Deep Agents native synchronous delegation, durable children and read-only policy."""

from pathlib import Path

import pytest
from tests.adapters.snapshot.test_git_tree import git, repository
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.adapters.storage.layout import StorageLayout
from pycode.adapters.team.worktrees import WorktreeManager
from pycode.app.config import AppConfig
from pycode.app.container import open_agent_session
from pycode.app.team_runtime import ConfiguredWorkers
from pycode.core.messages import Message


def finished(text):
    response = chat_completion()
    response.body = response.body.replace(
        b'"content": "done"', ('"content": "' + text + '"').encode()
    )
    return response


async def test_modifying_worker_uses_owned_worktree_native_write_and_keeps_parent_unchanged(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    project = repository(tmp_path)
    base = project / "base.py"
    base.write_text("base\n")
    git(project, "add", ".")
    git(project, "commit", "-qm", "base")
    head = git(project, "rev-parse", "HEAD")
    config = AppConfig(project, tmp_path / "data", memory_mode="off")
    layout = StorageLayout(config.workspace, config.user_data)
    worktrees = WorktreeManager(project, layout.project / "worktrees")
    with protocol_server(
        [
            skill_reply(
                "write_file",
                {"file_path": "/workspace/new.py", "content": "worker change\n"},
                "write",
            ),
            finished("created file; tests not run"),
        ]
    ) as (url, requests):
        workers = ConfiguredWorkers(
            config, (provider(url),), open_agent_session, worktrees, layout.runtime_database
        )
        worker = await workers.create("thread-1", "worker")
        try:
            result = await worker.run("Create new.py")
            assert result.outcome == "completed" and len(requests) == 2
            record = await worktrees.inspect(worker.worktree_id)
            root = Path(record["path"])
            changed = root / "new.py"
            assert changed.read_text() == "worker change\n"
            parent_changed = project / "new.py"
            assert not parent_changed.exists() and git(project, "rev-parse", "HEAD") == head
            values = await worker.values()
            assert record["branch"] in values["messages"][-1]["content"]
            assert "tests not run" in values["messages"][-1]["content"]
            usage = await worker.session.usage()
            assert usage["session"]["request_count"] == 2
        finally:
            await worker.aclose()
        retained = await worktrees.inspect(worker.worktree_id)
        assert retained["worker_stopped"] and retained["dirty"]
        assert not (await worktrees.cleanup(worker.worktree_id))["removed"]


@pytest.mark.parametrize("write_attempt", [False, True])
async def test_native_task_has_separate_child_history_readonly_files_and_metering(
    tmp_path, monkeypatch, write_attempt
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    source = tmp_path / "code.py"
    source.write_text("original code\n")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    child_tool = (
        skill_reply(
            "write_file", {"file_path": "/workspace/code.py", "content": "bad"}, "child-write"
        )
        if write_attempt
        else skill_reply("read_file", {"file_path": "/workspace/code.py"}, "child-read")
    )
    replies = [
        skill_reply(
            "task", {"description": "Inspect code.py", "subagent_type": "readonly"}, "delegate"
        ),
        child_tool,
        finished("child finished"),
        finished("parent finished"),
    ]
    with protocol_server(replies) as (url, requests):
        session = await open_agent_session(
            config, (provider(url),), "lead", create=True, refresh_models=False
        )
        try:
            result = await session.run(Message("u1", "user", "Review the project"))
            assert result.outcome == "completed" and result.final_text == "parent finished"
            assert len(requests) == 4
            assert source.read_text() == "original code\n"
            assert "write_file" not in str(requests[1]["body"]["tools"])
            view = await session.inspect()
            delegates = [
                entry for entry in view["snapshot"]["entries"] if entry["type"] == "team_delegate"
            ]
            assert len(delegates) == 2 and delegates[-1]["data"]["status"] == "completed"
            child_id = delegates[-1]["data"]["session_id"]
            usage = await session.usage()
            assert usage["session"]["request_count"] == 2
            assert usage["team"]["request_count"] == 2
            assert usage["team_turn"]["request_count"] == 2
            assert usage["total_with_team"]["request_count"] == 4
        finally:
            await session.aclose()
        child = await open_agent_session(config, (provider(url),), child_id, refresh_models=False)
        try:
            child_view = await child.inspect()
            tool_results = []
            for entry in child_view["snapshot"]["entries"]:
                message = entry.get("message")
                if message and message["tool_result"]:
                    tool_results.append(message["tool_result"])
            assert len(tool_results) == 1
            assert tool_results[0]["is_error"] is write_attempt
            if write_attempt:
                assert tool_results[0]["error_code"] == "unknown_tool"
            else:
                assert "original code" in tool_results[0]["text"]
        finally:
            await child.aclose()
