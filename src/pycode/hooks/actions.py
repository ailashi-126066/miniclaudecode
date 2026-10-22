"""Hooks actions: component-owned implementation."""

# Source: MewCode local hooks/executors.py; SHA-256:
# 2b16275a172f0c998ee24da7b693c3aa0bb248e374bff66e7b027c15672806f3
# Adaptation: owned native processes, bounded outputs and no fake agent success.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com

import os
import re
import sys
from dataclasses import replace
from pathlib import Path

from pycode.kernel import ToolContext, ToolError, ToolResult, json_dumps, plain_data
from pycode.sandbox import (
    ProcessRunner,
    subprocess_environment,
    subprocess_policy_hash,
    validate_passthrough,
)

TOKENS = (
    r"\$ENV\.[A-Z_][A-Z0-9_]*|\$TOOL_ARGS\.[A-Za-z0-9_-]+|"
    r"\$(?:EVENT|TOOL_NAME|FILE_PATH|MESSAGE|ERROR)\b"
)


class ConditionOutput:
    def __init__(self):
        self.content = bytearray()

    async def append(self, data: bytes) -> None:
        if len(self.content) + len(data) > 2048:
            raise ToolError("hook_condition_failed", "Condition worker output exceeds limit")
        self.content.extend(data)

    async def finish(self, result: ToolResult) -> ToolResult:

        return replace(result, text=self.content.decode("utf-8", errors="replace"))

    async def abort(self) -> None:
        self.content.clear()


class ConfiguredHookActions:
    def __init__(
        self,
        outputs,
        shell,
        sandbox,
        settings,
        environment,
        *,
        process_runner: ProcessRunner,
        passthrough=(),
    ):
        self._outputs = outputs
        self._run_process = process_runner
        self._shell = shell
        self._sandbox = sandbox
        self._settings = settings
        self._environment = environment
        self._passthrough = validate_passthrough(passthrough)
        self.policy_hash = subprocess_policy_hash(settings, self._passthrough)

    async def matches(self, hook, context):
        if hook.condition is None:
            return True
        result = await self._run_process(
            (
                sys.executable,
                "-X",
                "utf8",
                "-I",
                str(Path(__file__).with_name("conditions.py")),
            ),
            context.workspace,
            ConditionOutput(),
            ToolResult("condition", "hook_condition", ""),
            limit_seconds=hook.action.timeout,
            stdin=json_dumps(
                {"condition": plain_data(hook.condition), "fields": context.fields()}
            ).encode(),
            prefix="hook_condition",
            environment=subprocess_environment(os.environ, passthrough=self._passthrough),
        )
        if result.is_error:
            raise ToolError(
                result.error_code or "hook_condition_failed", "Hook condition failed or timed out"
            )
        return result.text.strip() == "true"

    def token(self, token, context):
        if not token.startswith("$ENV."):
            return context.expand(token)
        name = token[len("$ENV.") :]
        if name not in self._environment:
            raise ToolError("hook_environment_missing", "Hook environment reference is unavailable")
        return self._environment[name]

    def command(self, template, context):
        # Only explicit $ENV.NAME references reach the command; nothing else is inherited.
        environment = subprocess_environment(os.environ, passthrough=self._passthrough)
        index = 0

        def bind(match):
            nonlocal index
            name = "PYCODE_HOOK_VALUE_" + str(index)
            index += 1
            environment[name] = self.token(match.group(0), context)
            return "${" + name + "}"

        return re.sub(TOKENS, bind, template), environment

    async def execute(self, action, context, tool: ToolContext):
        kind = action["type"]
        if kind == "prompt":
            # Environment secrets are not expanded into model context.
            if "$ENV." in action["message"]:
                raise ToolError(
                    "invalid_hook_prompt", "Prompt actions cannot inject environment secrets"
                )
            return ToolResult(tool.call_id, tool.tool_name, context.expand(action["message"]))
        if kind == "command":
            command, environment = self.command(action["command"], context)
            argv = (self._shell, "-c", command)
            argv = await self._sandbox.launch(argv, tool.workspace, self._settings)
            writer = await self._outputs.begin(tool, "head")
            return await self._run_process(
                argv,
                tool.workspace,
                writer,
                ToolResult(tool.call_id, tool.tool_name, ""),
                limit_seconds=action["timeout"],
                prefix="hook_command",
                environment=environment,
            )
        raise ToolError("unknown_hook_action", "Unknown Hook action")
