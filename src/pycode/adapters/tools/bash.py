# Source: MewCode local mewcode/tools/bash.py; SHA-256:
# 24e05e37c804c4fc9bec2a50e95090b33fd4f1f89d4d7c7baeddeae79d9b84b3
# Source: MewCode local mewcode/processes.py; SHA-256:
# a8049900efb1977eb4866bd871d3bcd101abaffe35d70452c75da69da45096b4
# Adaptation: explicit shell/output dependencies, streamed output and owned native process groups.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Shell tool with bounded streaming output; execution goes through ToolExecutor."""

import os
import shutil
from dataclasses import replace
from pathlib import Path

from pycode.adapters.tools.process_runner import run_process
from pycode.core.errors import ToolError
from pycode.core.hashing import data_hash
from pycode.core.messages import ToolResult
from pycode.core.sandbox import SandboxSettings
from pycode.core.subprocess_env import subprocess_environment
from pycode.core.tools import ToolContext, ToolSpec
from pycode.core.values import plain_data
from pycode.ports.outputs import ToolOutputStore
from pycode.ports.sandbox import SandboxBackend


def find_bash() -> str:
    if os.name == "nt":
        git = shutil.which("git")
        if git:
            bundled = Path(git).parent.parent / "bin" / "bash.exe"
            if bundled.is_file():
                return str(bundled)
    executable = shutil.which("bash")
    if not executable:
        raise ToolError("shell_unavailable", "Bash executable is unavailable")
    return executable


class Bash:
    spec = ToolSpec(
        name="Bash",
        description="Run a shell command with complete output storage and a bounded tail preview.",
        category="command",
        execution_mode="sequential",
        input_schema={
            "type": "object",
            "properties": {
                "command": {"type": "string", "minLength": 1},
                "timeout": {"type": "number", "minimum": 0.1, "maximum": 600, "default": 120},
            },
            "required": ["command"],
            "additionalProperties": False,
        },
    )

    def __init__(
        self,
        executable: str,
        outputs: ToolOutputStore,
        sandbox: SandboxBackend | None = None,
        settings: SandboxSettings | None = None,
        environment=None,
    ) -> None:
        self._executable = executable
        self._outputs = outputs
        self._sandbox = sandbox
        self._settings = settings or SandboxSettings()
        self._environment = environment
        if self._settings.mode != "off":
            self.spec = replace(self.spec, policy_hash=data_hash(plain_data(self._settings)))

    async def execute(self, context: ToolContext, arguments: dict) -> ToolResult:
        argv = (self._executable, "-c", arguments["command"])
        if self._sandbox is not None:
            argv = await self._sandbox.launch(argv, context.workspace, self._settings)
        writer = await self._outputs.begin(context, "tail")
        return await run_process(
            argv,
            context.workspace,
            writer,
            ToolResult(context.call_id, self.spec.name, ""),
            limit_seconds=arguments.get("timeout", 120),
            progress=context.progress,
            # Never inherit provider keys or other credentials from the parent process.
            environment=subprocess_environment(
                os.environ if self._environment is None else self._environment
            ),
        )
