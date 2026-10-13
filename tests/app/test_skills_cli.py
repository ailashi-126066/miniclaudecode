import asyncio

from tests.app.test_execution_cli import setup_cli
from tests.services.test_skills import write_skill
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_skills_http import skill_reply

from pycode.app.cli import main


def test_cli_load_search_reload_use_public_session_and_make_no_model_requests(
    tmp_path, monkeypatch, capsys
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    asyncio.run(write_skill(tmp_path / "data/skills"))
    with protocol_server(
        [
            chat_completion(),
            skill_reply("read_file", {"file_path": "/skills/user/example/SKILL.md"}, "load"),
            chat_completion(),
        ]
    ) as (url, requests):
        common, execution = setup_cli(tmp_path, provider(url))
        assert main([*common, "run", "task", *execution, "--create"]) == 0
        capsys.readouterr()
        assert main([*common, "skill", *execution, "load", "example", "arguments"]) == 0
        assert "completed" in capsys.readouterr().out
        assert main([*common, "skill", *execution, "search", "Example", "--limit", "1"]) == 0
        assert "Example task" in capsys.readouterr().out
        assert main([*common, "skill", *execution, "reload"]) == 0
        capsys.readouterr()
        assert main([*common, "skill", *execution, "search", "", "--limit", "0"]) == 1
        assert "invalid_skill_search" in capsys.readouterr().out
        assert len(requests) == 3
        assert "Task arguments: arguments" in str(requests[1]["body"]["messages"])
        assert "Instructions $ARGUMENTS" in str(requests[2]["body"]["messages"])
