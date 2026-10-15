# Source: MewCode local hooks/executors.py; SHA-256:
# 2b16275a172f0c998ee24da7b693c3aa0bb248e374bff66e7b027c15672806f3
# Adaptation: owned native processes, cancellable HTTP, bounded outputs and no fake agent success.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Configured commands and HTTP requests share controlled execution and output limits."""

import asyncio
import os
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from pycode.adapters.tools.process_runner import run_process
from pycode.core.errors import StorageError, ToolError
from pycode.core.messages import ToolResult
from pycode.core.subprocess_env import subprocess_environment
from pycode.core.tools import ToolContext
from pycode.core.values import json_dumps, plain_data

MAX_HTTP_BYTES = 50 * 1024
TOKENS = (
    r"\$ENV\.[A-Z_][A-Z0-9_]*|\$TOOL_ARGS\.[A-Za-z0-9_-]+|"
    r"\$(?:EVENT|TOOL_NAME|FILE_PATH|MESSAGE|ERROR)\b"
)


class ConditionOutput:
    def __init__(self):
        self.content = bytearray()

    async def append(self, content):
        if len(self.content) + len(content) > 2048:
            raise ToolError("hook_condition_failed", "Condition worker output exceeds limit")
        self.content.extend(content)

    async def finish(self, result):
        from dataclasses import replace

        return replace(result, text=self.content.decode("utf-8", errors="replace"))

    async def abort(self):
        self.content.clear()


class ConfiguredHookActions:
    def __init__(self, outputs, shell, sandbox, settings, environment, delegate=None):
        self._outputs = outputs
        self._shell = shell
        self._sandbox = sandbox
        self._settings = settings
        self._environment = environment
        self._delegate = delegate

    async def matches(self, hook, context):
        if hook.condition is None:
            return True
        result = await run_process(
            (
                sys.executable,
                "-X",
                "utf8",
                "-I",
                str(Path(__file__).with_name("condition_worker.py")),
            ),
            context.workspace,
            ConditionOutput(),
            ToolResult("condition", "hook_condition", ""),
            limit_seconds=hook.action.timeout,
            stdin=json_dumps(
                {"condition": plain_data(hook.condition), "fields": context.fields()}
            ).encode(),
            prefix="hook_condition",
            environment=subprocess_environment(os.environ),
        )
        if result.is_error:
            raise ToolError(
                result.error_code or "hook_condition_failed", "Hook condition failed or timed out"
            )
        return result.text.strip() == "true"

    def expand(self, value, context):
        return re.sub(TOKENS, lambda match: self.token(match.group(0), context), value)

    def token(self, token, context):
        if not token.startswith("$ENV."):
            return context.expand(token)
        name = token[len("$ENV.") :]
        if name not in self._environment:
            raise ToolError("hook_environment_missing", "Hook environment reference is unavailable")
        return self._environment[name]

    def command(self, template, context):
        # Only explicit $ENV.NAME references reach the command; nothing else is inherited.
        environment = subprocess_environment(os.environ)
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
        if kind == "agent":
            if self._delegate is None:
                raise ToolError(
                    "hook_agent_unavailable", "This Worker cannot delegate another Hook"
                )
            if "$ENV." in action["prompt"]:
                raise ToolError(
                    "invalid_hook_prompt", "Agent prompts cannot contain environment secrets"
                )
            from langchain_core.messages import HumanMessage

            async with asyncio.timeout(action["timeout"]):
                response = await self._delegate.invoke(
                    {
                        "messages": [HumanMessage(content=context.expand(action["prompt"]))],
                    }
                )
            return ToolResult(tool.call_id, tool.tool_name, response["messages"][-1].content)
        if kind == "command":
            command, environment = self.command(action["command"], context)
            argv = (self._shell, "-c", command)
            argv = await self._sandbox.launch(argv, tool.workspace, self._settings)
            writer = await self._outputs.begin(tool, "head")
            return await run_process(
                argv,
                tool.workspace,
                writer,
                ToolResult(tool.call_id, tool.tool_name, ""),
                limit_seconds=action["timeout"],
                prefix="hook_command",
                environment=environment,
            )
        return await self.http(action, context, tool)

    async def http(self, action, context, tool):
        url = self.expand(action["url"], context)
        try:
            parsed = urlsplit(url)
            valid_port = parsed.port is None or 0 < parsed.port < 65536
        except ValueError as error:
            raise ToolError("invalid_hook_url", "Expanded Hook URL is malformed") from error
        if (
            not valid_port
            or parsed.scheme not in {"http", "https"}
            or not parsed.hostname
            or parsed.username
            or parsed.password
        ):
            raise ToolError("invalid_hook_url", "Expanded Hook URL is not HTTP(S)")
        headers = {key: self.expand(value, context) for key, value in action["headers"].items()}
        body = self.expand(action["body"], context).encode() if action["body"] else None
        if body and "Content-Type" not in headers:
            headers["Content-Type"] = "application/json"
        writer = await self._outputs.begin(tool, "head")
        try:
            async with httpx.AsyncClient(timeout=action["timeout"], trust_env=False) as client:
                async with client.stream(
                    action["method"], url, headers=headers, content=body
                ) as response:
                    size, truncated = 0, False
                    await writer.append(f"HTTP {response.status_code}\n".encode())
                    async for content in response.aiter_bytes():
                        remaining = MAX_HTTP_BYTES - size
                        await writer.append(content[:remaining])
                        size += min(len(content), remaining)
                        if len(content) > remaining:
                            truncated = True
                            break
                    if truncated:
                        await writer.append(b"\n[Hook HTTP response reached its byte limit]\n")
                    return await writer.finish(
                        ToolResult(
                            tool.call_id,
                            tool.tool_name,
                            "",
                            is_error=not 200 <= response.status_code < 300,
                            error_code="hook_http_status"
                            if not 200 <= response.status_code < 300
                            else None,
                            details={"http_status": response.status_code, "truncated": truncated},
                        )
                    )
        except (httpx.ConnectError, httpx.InvalidURL) as error:
            await writer.abort()
            raise ToolError("hook_http_connection", "Hook HTTP connection failed") from error
        except httpx.HTTPError as error:
            await writer.abort()
            raise StorageError(
                "hook_effects_uncertain", "Hook HTTP outcome is uncertain; review before retry"
            ) from error
        except BaseException:
            await writer.abort()
            raise
