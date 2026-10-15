import json
import os
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from pycode.app.cli import main
from pycode.app.cli_commands import command
from pycode.app.config import AppConfig
from pycode.kernel.contracts.completion import RunResult


def test_diagnostics_use_shared_application(tmp_path, capsys):
    assert main(["--workspace", str(tmp_path), "doctor", "--json"]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["name"] == "pycode"
    assert result["workspace"]["state_path"] == str(tmp_path / ".pycode")
    assert not result["workspace"]["is_git"]
    assert not (tmp_path / ".git").exists()


def test_doctor_reports_external_tools_with_install_hints(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path / "empty-bin"))
    assert main(["--workspace", str(tmp_path), "doctor", "--json"]) == 0
    tools = {tool["name"]: tool for tool in json.loads(capsys.readouterr().out)["tools"]}
    assert {"rg", "git", "bash"} <= tools.keys()
    assert tools["rg"]["path"] is None and "ripgrep" in tools["rg"]["install"]
    assert main(["--workspace", str(tmp_path), "doctor"]) == 0
    assert "rg: missing (glob and grep search tools)" in capsys.readouterr().out


def test_missing_workspace_is_a_reported_failure(tmp_path, capsys):
    assert main(["--workspace", str(tmp_path / "missing"), "doctor", "--json"]) == 1
    assert not json.loads(capsys.readouterr().out)["workspace"]["exists"]


def test_module_entry_point(tmp_path):
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(Path(__file__).resolve().parents[2] / "src")
    result = subprocess.run(
        [sys.executable, "-m", "pycode", "--version"],
        cwd=tmp_path,
        env=environment,
        capture_output=True,
        text=True,
        check=True,
    )
    assert result.stdout.strip() == "pycode 0.1.0"


async def test_cli_prints_forwarded_child_approval_and_exits_two(tmp_path, monkeypatch, capsys):

    session = SimpleNamespace(
        run=AsyncMock(return_value=RunResult("completed", "lead finished", ())),
        wait_team=AsyncMock(return_value="blocked"),
        inspect=AsyncMock(
            return_value={"team": {"approvals": [{"agent_id": "12345678", "role": "tester"}]}}
        ),
        publish_result=AsyncMock(),
        aclose=AsyncMock(),
    )
    monkeypatch.setattr(
        "pycode.app.cli_commands.open_agent_session", AsyncMock(return_value=session)
    )
    monkeypatch.setattr("pycode.app.cli_commands.read_providers", lambda path: ())
    args = SimpleNamespace(
        command="run",
        output_format="json",
        providers="unused",
        session="lead",
        create=False,
        cached=True,
        prompt="task",
        input_id="u1",
    )
    assert await command(args, AppConfig(tmp_path, tmp_path / "data")) == 2
    assert "12345678" in capsys.readouterr().out
    session.aclose.assert_awaited_once()
