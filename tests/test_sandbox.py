from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from mewcode.app import MewCodeApp
from mewcode.commands.handlers.sandbox import handle_sandbox
from mewcode.commands.registry import CommandContext
from mewcode.config import SandboxAppConfig
from mewcode.permissions import DangerousCommandDetector, PathSandbox, PermissionChecker, RuleEngine
from mewcode.sandbox import SandboxConfig, create_sandbox
from mewcode.sandbox.seatbelt import SeatbeltSandbox
from mewcode.tools import create_default_registry
from mewcode.tools.bash import Bash, Params


def make_checker(tmp_path):
    return PermissionChecker(
        detector=DangerousCommandDetector(),
        sandbox=PathSandbox(str(tmp_path)),
        rule_engine=RuleEngine(),
        sandbox_enabled=True,
    )


@pytest.mark.parametrize("system", ["Linux", "Windows", "FreeBSD"])
def test_other_platforms_do_not_create_os_sandbox(monkeypatch, system):
    monkeypatch.setattr("mewcode.sandbox.platform.system", lambda: system)
    assert create_sandbox() is None


def test_macos_creates_seatbelt(monkeypatch):
    monkeypatch.setattr("mewcode.sandbox.platform.system", lambda: "Darwin")
    assert isinstance(create_sandbox(), SeatbeltSandbox)


@pytest.mark.parametrize("system,available", [("Linux", True), ("Darwin", False)])
def test_startup_never_auto_allows_without_a_working_backend(tmp_path, monkeypatch, system, available):
    monkeypatch.setattr("mewcode.sandbox.platform.system", lambda: system)
    monkeypatch.setattr(SeatbeltSandbox, "available", lambda self: available)
    registry = create_default_registry()
    bash = registry.get("Bash")
    bash.sandbox = SeatbeltSandbox()
    bash.sandbox_config = SandboxConfig()
    app = SimpleNamespace(registry=registry, _sandbox_cfg=SandboxAppConfig(enabled=True, auto_allow=True))
    checker = make_checker(tmp_path)

    MewCodeApp._configure_sandbox(app, checker, str(tmp_path))

    assert not checker.sandbox_enabled
    assert bash.sandbox is None
    assert bash.sandbox_config is None
    assert checker.check(bash, {"command": "npm test"}).effect == "ask"


def test_startup_binds_seatbelt_before_allowing_bash(tmp_path, monkeypatch):
    monkeypatch.setattr("mewcode.sandbox.platform.system", lambda: "Darwin")
    monkeypatch.setattr(SeatbeltSandbox, "available", lambda self: True)
    registry = create_default_registry()
    settings = SandboxAppConfig(enabled=True, auto_allow=True, network_enabled=True)
    app = SimpleNamespace(registry=registry, _sandbox_cfg=settings)
    checker = make_checker(tmp_path)

    MewCodeApp._configure_sandbox(app, checker, str(tmp_path))

    bash = registry.get("Bash")
    assert bash.sandbox_active
    assert bash.sandbox_config.network_enabled
    assert str(tmp_path) in bash.sandbox_config.allow_write
    assert checker.check(bash, {"command": "npm test"}).effect == "allow"
    external = SimpleNamespace(name="mcp__service__write", category="command")
    assert checker.check(external, {}).effect == "ask"

    settings.enabled = False
    MewCodeApp._configure_sandbox(app, checker, str(tmp_path))
    assert not checker.sandbox_enabled
    assert not bash.sandbox_active


def test_auto_allow_flag_alone_cannot_skip_approval(tmp_path):
    checker = make_checker(tmp_path)
    assert checker.check(Bash(), {"command": "npm test"}).effect == "ask"


@pytest.mark.asyncio
async def test_backend_disappearance_does_not_run_unwrapped_command(monkeypatch):
    monkeypatch.setattr(SeatbeltSandbox, "available", lambda self: False)
    spawn = AsyncMock()
    monkeypatch.setattr("mewcode.tools.bash.asyncio.create_subprocess_shell", spawn)
    bash = Bash()
    bash.sandbox = SeatbeltSandbox()
    bash.sandbox_config = SandboxConfig()

    result = await bash.execute(Params(command="npm test"))

    assert result.is_error
    assert "sandbox is unavailable" in result.output
    spawn.assert_not_called()


@pytest.mark.asyncio
async def test_command_status_distinguishes_sandbox_from_auto_approval(tmp_path, monkeypatch):
    monkeypatch.setattr("mewcode.sandbox.platform.system", lambda: "Darwin")
    monkeypatch.setattr(SeatbeltSandbox, "available", lambda self: True)
    messages = []
    agent = SimpleNamespace(
        registry=create_default_registry(), permission_checker=make_checker(tmp_path), work_dir=str(tmp_path)
    )
    ctx = CommandContext(
        args="on", agent=agent, conversation=None, session=None, session_manager=None,
        memory_manager=None, ui=SimpleNamespace(add_system_message=messages.append, refresh_status=lambda: None),
        config={},
    )
    await handle_sandbox(ctx)
    ctx.args = ""
    await handle_sandbox(ctx)
    assert "OS 沙箱: 已启用" in messages[-1]
    assert "自动放行: 否" in messages[-1]

    ctx.args = "off"
    await handle_sandbox(ctx)
    assert not agent.registry.get("Bash").sandbox_active
    assert not agent.permission_checker.sandbox_enabled


@pytest.mark.asyncio
async def test_command_rejects_non_macos(tmp_path, monkeypatch):
    monkeypatch.setattr("mewcode.sandbox.platform.system", lambda: "Linux")
    messages = []
    checker = make_checker(tmp_path)
    checker.sandbox_enabled = False
    ctx = CommandContext(
        args="on-auto", agent=SimpleNamespace(registry=create_default_registry(), permission_checker=checker),
        conversation=None, session=None, session_manager=None, memory_manager=None,
        ui=SimpleNamespace(add_system_message=messages.append, refresh_status=lambda: None), config={},
    )
    await handle_sandbox(ctx)
    assert "仅支持 macOS" in messages[-1]
    assert not checker.sandbox_enabled
