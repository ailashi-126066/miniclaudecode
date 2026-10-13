import asyncio
import json
from dataclasses import asdict
from pathlib import Path

import pytest

from pycode.adapters.hooks.actions import ConfiguredHookActions
from pycode.adapters.hooks.config import parse_hooks
from pycode.adapters.sandbox.seatbelt import UnavailableSandbox
from pycode.adapters.tools.bash import find_bash
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.core.errors import ToolError
from pycode.core.hooks import HookAction, HookContext
from pycode.core.sandbox import SandboxSettings
from pycode.core.tools import ToolContext

VECTORS = json.loads(
    (Path(__file__).parents[2] / "fixtures/hook-baselines.json").read_text(encoding="utf-8")
)


def actions(tmp_path, environment=None):
    return ConfiguredHookActions(
        FileToolOutputStore(tmp_path / "outputs", "s1"),
        find_bash(),
        UnavailableSandbox(),
        SandboxSettings(),
        environment or {},
    )


async def test_source_condition_vectors_use_owned_worker(tmp_path):
    runner = actions(tmp_path)
    for expression, fields, expected in VECTORS["conditions"]:
        hook = parse_hooks([{**VECTORS["config"], "if": expression}])[0]
        context = HookContext(
            "s1",
            "r1",
            str(tmp_path),
            "b",
            "pre_tool_use",
            fields.get("tool", ""),
            {key[5:]: value for key, value in fields.items() if key.startswith("args.")},
        )
        assert await runner.matches(hook, context) == expected


async def test_regex_condition_timeout_does_not_block_owner(tmp_path):
    runner = actions(tmp_path)
    hook = parse_hooks(
        [
            {
                **VECTORS["config"],
                "if": "args.command =~ /(a+)+$/",
                "action": {"type": "prompt", "message": "blocked", "timeout": 0.2},
            }
        ]
    )[0]
    context = HookContext(
        "s1", "r1", str(tmp_path), "b", "pre_tool_use", "Bash", {"command": "a" * 500 + "!"}
    )
    async with asyncio.timeout(5):
        with pytest.raises(ToolError) as error:
            await runner.matches(hook, context)
    assert error.value.code == "hook_condition_timeout"


async def test_command_variables_are_data_and_cannot_inject_shell_or_environment_expansion(
    tmp_path,
):
    runner = actions(tmp_path, {"SECRET": "must not leak"})
    value = "$(touch injected); $ENV.SECRET $EVENT"
    context = HookContext("s1", "r1", str(tmp_path), "b", "post_receive", message=value)
    tool = ToolContext("s1", "r1", "op", str(tmp_path), "call", "hook__test")
    result = await runner.execute(
        asdict(HookAction("command", command='printf "%s" "$MESSAGE"')), context, tool
    )
    assert not result.is_error and result.text == value
    assert not (tmp_path / "injected").exists()
    assert runner.expand("$MESSAGE", context) == value
