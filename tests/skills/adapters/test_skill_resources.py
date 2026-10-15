import asyncio
import json
import os
import subprocess
import sys
from dataclasses import asdict

import pytest

from pycode.app.bootstrap import compose_application
from pycode.app.configuration.settings import AppConfig
from pycode.kernel.domain.messages import Message
from tests.context.adapters.test_summary_http import seed
from tests.skills.adapters.test_skills_http import skill_reply
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.support.skill_resources import VECTORS, write_resource_skill


def results(state):
    return [
        entry["message"]["tool_result"]
        for entry in state["snapshot"]["entries"]
        if entry["message"] and entry["message"]["role"] == "tool"
    ]


@pytest.mark.parametrize("kind", ["sync", "async"])
async def test_dynamic_schema_and_python_execute_survive_approval_restart(
    tmp_path, monkeypatch, kind
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await write_resource_skill(config.user_data / "skills", kind=kind)
    replies = [
        skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "load"),
        skill_reply("resource_probe", VECTORS["argument"], "probe"),
        chat_completion(),
    ]
    with protocol_server(replies) as (url, requests):
        configured = provider(url)
        session = await compose_application(config, (configured,)).open_session(
            "s1", create=True, refresh_models=False
        )
        try:
            waiting = await session.run(Message("u1", "user", "use resource"))
            assert waiting.outcome == "needs_input" and len(requests) == 2
            assert not (tmp_path / "imported.txt").exists()
            assert not any(
                tool["function"]["name"] == "resource_probe"
                for tool in requests[0]["body"]["tools"]
            )
            declaration = next(
                tool["function"]
                for tool in requests[1]["body"]["tools"]
                if tool["function"]["name"] == "resource_probe"
            )
            assert declaration["parameters"] == VECTORS["declaration"]["parameters"]
            assert (await session.resume()).pending == waiting.pending
            pending = waiting.pending[0]
            done = await session.resume(
                {"operation_id": pending["operation_id"], "decision": "approve"}
            )
            assert done.outcome == "completed" and len(requests) == 3
            assert (tmp_path / "imported.txt").read_text() == "imported"
            settled = results(await session.inspect())
            assert settled[-1]["text"].strip() == VECTORS["expected"]
            assert (
                settled[-1]["output_id"] and settled[-1]["operation_id"] == pending["operation_id"]
            )
            report = (await session.inspect())["context_usage"]
            assert report["source"] == "provider_usage" and report["tokens"] == 25
        finally:
            await session.aclose()
        view = await compose_application(config).open_view("s1")
        try:
            assert (await view.inspect())["context_usage"] == report
        finally:
            await view.aclose()


@pytest.mark.parametrize(
    "argument,expected",
    [
        ({}, "invalid_arguments"),
        ({"detail": 1}, "invalid_arguments"),
        (VECTORS["argument"], "permission_denied"),
    ],
)
async def test_invalid_arguments_and_denial_never_import_resource(
    tmp_path, monkeypatch, argument, expected
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await write_resource_skill(config.user_data / "skills")
    with protocol_server(
        [
            skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "load"),
            skill_reply("resource_probe", argument, "probe"),
            chat_completion(),
        ]
    ) as (
        url,
        requests,
    ):
        session = await compose_application(config, (provider(url),)).open_session(
            "s1", create=True, refresh_models=False
        )
        try:
            done = await session.run(Message("u1", "user", "execute"))
            if done.outcome == "needs_input":
                done = await session.resume(
                    {"operation_id": done.pending[0]["operation_id"], "decision": "deny"}
                )
            assert done.outcome == "completed" and len(requests) == 3
            assert results(await session.inspect())[-1]["error_code"] == expected
            assert not (tmp_path / "imported.txt").exists()
        finally:
            await session.aclose()


@pytest.mark.parametrize("change", ["implementation", "schema", "missing"])
async def test_pending_resume_rejects_changed_resource_and_can_resume_restored_exact_bytes(
    tmp_path, monkeypatch, change
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    directory = await write_resource_skill(config.user_data / "skills")
    with protocol_server(
        [
            skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "load"),
            skill_reply("resource_probe", VECTORS["argument"], "probe"),
            chat_completion(),
            skill_reply("resource_probe", VECTORS["argument"], "fresh-probe"),
            chat_completion(),
        ]
    ) as (url, requests):
        configured = provider(url)
        session = await compose_application(config, (configured,)).open_session(
            "s1", create=True, refresh_models=False
        )
        waiting = await session.run(Message("u1", "user", "execute"))
        target = directory / ("tool.json" if change == "schema" else "references/resource_probe.py")
        original = await asyncio.to_thread(target.read_bytes)
        if change == "missing":
            await asyncio.to_thread(target.unlink)
        else:
            await asyncio.to_thread(target.write_bytes, original + b" ")
        try:
            rejected = await session.resume(
                {"operation_id": waiting.pending[0]["operation_id"], "decision": "approve"}
            )
            assert rejected.outcome == "completed"
            assert results(await session.inspect())[-1]["error_code"] == "skill_resources_changed"
            assert len(requests) == 3 and not (tmp_path / "imported.txt").exists()
            await asyncio.to_thread(target.write_bytes, original)
            fresh = await session.run(Message("fresh", "user", "Execute restored resource"))
            assert fresh.outcome == "needs_input"
            assert fresh.pending[0]["operation_id"] != waiting.pending[0]["operation_id"]
            assert (
                await session.resume(
                    {"operation_id": fresh.pending[0]["operation_id"], "decision": "approve"}
                )
            ).outcome == "completed"
            assert len(requests) == 5
        finally:
            await session.aclose()


async def test_explicit_reload_updates_resource_implementation_without_changing_instructions(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    directory = await write_resource_skill(config.user_data / "skills")
    replies = [
        skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "old-read"),
        chat_completion(),
        skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "new-read"),
        chat_completion(),
    ]
    with protocol_server(replies) as (url, requests):
        session = await compose_application(config, (provider(url),)).open_session(
            "s1", create=True, refresh_models=False
        )
        try:
            assert (await session.load_skill("resource")).outcome == "completed"
            first = next(
                entry["data"]
                for entry in (await session.inspect())["snapshot"]["entries"]
                if entry["type"] == "skill_load"
            )
            await asyncio.to_thread(
                (directory / "references/resource_probe.py").write_text,
                "def execute(detail):\n    return 'new:' + detail\n",
                encoding="utf-8",
            )
            unchanged = next(
                entry["data"]
                for entry in (await session.inspect())["snapshot"]["entries"]
                if entry["type"] == "skill_load"
            )
            assert unchanged == first
            await session.reload_skills()
            assert (await session.load_skill("resource")).outcome == "completed"
            loaded = [
                entry["data"]
                for entry in (await session.inspect())["snapshot"]["entries"]
                if entry["type"] == "skill_load"
            ]
            second = loaded[-1]
            assert (
                first["content_hash"] == second["content_hash"]
                and first["loaded_hash"] != second["loaded_hash"]
            )
            assert (
                first["resource_tools"][0]["schema_hash"]
                == second["resource_tools"][0]["schema_hash"]
            )
            assert (
                first["resource_tools"][0]["implementation_hash"]
                != second["resource_tools"][0]["implementation_hash"]
            )
            assert len(requests) == 4 and not (tmp_path / "imported.txt").exists()
        finally:
            await session.aclose()


@pytest.mark.parametrize("phase", ["ready", "output"])
async def test_real_process_exit_reuses_resource_result_without_repeating_effect(
    tmp_path, monkeypatch, phase
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    code = (
        "from pathlib import Path\n"
        "def execute(detail):\n    path = Path('count.txt')\n"
        "    count = int(path.read_text()) if path.exists() else 0\n"
        "    path.write_text(str(count + 1))\n    return 'count:' + str(count + 1)\n"
    )
    await write_resource_skill(config.user_data / "skills", code=code)
    with protocol_server(
        [
            skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "load"),
            skill_reply("resource_probe", VECTORS["argument"], "probe"),
            chat_completion(),
        ]
    ) as (url, requests):
        configured = provider(url)
        request = {
            "workspace": str(tmp_path),
            "data": str(config.user_data),
            "provider": asdict(configured),
            "phase": phase,
        }
        child = await asyncio.to_thread(
            subprocess.run,
            [sys.executable, "-m", "tests.support.skill_crash_worker"],
            input=json.dumps(request).encode(),
            capture_output=True,
            timeout=30,
            env=dict(os.environ),
        )
        assert child.returncode == 79, child.stderr.decode(errors="replace")
        assert (tmp_path / "count.txt").read_text() == "1" and len(requests) == 2
        session = await compose_application(config, (configured,)).open_session(
            "s1", refresh_models=False
        )
        try:
            done = await session.run(
                Message("new-task", "user", "Continue without repeating the resource")
            )
            assert done.outcome == "completed" and len(requests) == 3
            assert (tmp_path / "count.txt").read_text() == "1"
            stored = results(await session.inspect())
            assert len(stored) == 2
            if phase == "ready":
                assert stored[-1]["text"].strip() == "count:1"
            else:
                assert stored[-1]["error_code"] == "tool_interrupted"
                assert stored[-1]["details"]["output_recovered"]
            assert stored[-1]["output_id"] and stored[-1]["operation_id"]
        finally:
            await session.aclose()


async def test_compaction_checkpoint_and_branch_restore_own_resource_tool_set(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(
        tmp_path, tmp_path / "data", summary_keep_recent_tokens=1500, memory_mode="off"
    )
    await write_resource_skill(config.user_data / "skills")
    await seed(config)
    with protocol_server(
        [
            skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "load"),
            chat_completion(),
            chat_completion(),
            chat_completion(),
            chat_completion(),
        ]
    ) as (
        url,
        requests,
    ):
        session = await compose_application(config, (provider(url),)).open_session(
            "s1", refresh_models=False
        )
        try:
            before = (await session.inspect())["snapshot"]["leaf_id"]
            assert (await session.load_skill("resource")).outcome == "completed"
            loaded = next(
                entry["data"]
                for entry in (await session.inspect())["snapshot"]["entries"]
                if entry["type"] == "skill_load"
            )
            assert any(
                tool["function"]["name"] == "resource_probe"
                for tool in requests[1]["body"]["tools"]
            ), [tool["function"]["name"] for tool in requests[1]["body"]["tools"]]
            assert (await session.compact()).applied_job_id
            state = await session.inspect()
            checkpoint = next(
                entry["data"]["checkpoint"]
                for entry in state["snapshot"]["entries"]
                if entry["type"] == "compaction"
            )
            declaration = next(
                spec for spec in checkpoint["tools"] if spec["name"] == "resource_probe"
            )
            assert (
                declaration["implementation_hash"]
                == loaded["resource_tools"][0]["implementation_hash"]
            )
            assert checkpoint["skills"][0]["resource_tools"] == loaded["resource_tools"]
        finally:
            await session.aclose()
        session = await compose_application(config, (provider(url),)).open_session(
            "s1", refresh_models=False
        )
        try:
            assert (await session.run(Message("after", "user", "continue"))).outcome == "completed"
            assert any(
                tool["function"]["name"] == "resource_probe"
                for tool in requests[3]["body"]["tools"]
            )
            await session.branch(before)
            assert (await session.run(Message("branch", "user", "fresh"))).outcome == "completed"
            assert not any(
                tool["function"]["name"] == "resource_probe"
                for tool in requests[4]["body"]["tools"]
            )
            assert not (tmp_path / "imported.txt").exists()
        finally:
            await session.aclose()
