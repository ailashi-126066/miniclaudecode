# Source: MewCode local skills/directory.py; SHA-256:
# 2b04c95e8b75a23b8f65990b846e2ac76d08aaf5d18dadf55d76e0f7ff6b500f
# Adaptation: common executor and subprocess lifecycle; no import during registration.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Skill Python execution is a normal, approved, sequential external tool."""

import asyncio
import os
import sys
from pathlib import Path

from pycode.kernel import ToolResult, json_dumps, resolve_workspace_path
from pycode.sandbox import ProcessRunner, subprocess_environment
from pycode.skills.domain.skills import resource_spec
from pycode.skills.ports.sandbox import SkillReadGrant


class PythonSkillTool:
    def __init__(
        self,
        declaration,
        activation,
        catalog,
        outputs,
        sandbox,
        settings,
        *,
        process_runner: ProcessRunner,
        skill_read_grant: SkillReadGrant,
        timeout=120,
    ):
        self.spec = resource_spec(declaration)
        self._declaration = declaration
        self._activation = activation
        self._catalog = catalog
        self._outputs = outputs
        self._sandbox = sandbox
        self._settings = settings
        self._run_process = process_runner
        self._skill_read_grant = skill_read_grant
        self._timeout = timeout
        self._run_process = process_runner
        self._skill_read_grant = skill_read_grant

    async def execute(self, context, arguments):
        await self._catalog.verify_resources(self._activation)
        parameters = dict(arguments)
        # Preserve source workspace checks for its declared file arguments.
        for field in ("file_path", "resume_path"):
            if isinstance(parameters.get(field), str) and parameters[field]:
                owned = asyncio.create_task(
                    asyncio.to_thread(resolve_workspace_path, context.workspace, parameters[field])
                )
                try:
                    parameters[field] = str(await asyncio.shield(owned))
                except asyncio.CancelledError:
                    await owned
                    raise
        request = {
            **self._declaration,
            "resource_hash": self._activation["resource_hash"],
            "arguments": parameters,
        }
        argv = (
            sys.executable,
            "-X",
            "utf8",
            "-I",
            str(Path(__file__).with_name("python_worker.py")),
        )
        # Only the verified activation grants its exact source root; the injected
        # grant preserves mandatory worker write boundaries and credential denials.
        sandbox = self._skill_read_grant(self._sandbox, Path(self._activation["path"]).parent)
        argv = await sandbox.launch(argv, context.workspace, self._settings)
        writer = await self._outputs.begin(context, "head")
        return await self._run_process(
            argv,
            context.workspace,
            writer,
            ToolResult(context.call_id, self.spec.name, ""),
            limit_seconds=self._timeout,
            stdin=json_dumps(request, ensure_ascii=False).encode("utf-8"),
            prefix="skill_python",
            environment=subprocess_environment(os.environ),
            progress=context.progress,
        )


class PythonSkillToolFactory:
    def __init__(
        self,
        catalog,
        outputs,
        sandbox,
        settings,
        *,
        process_runner: ProcessRunner,
        skill_read_grant: SkillReadGrant,
    ):
        self._catalog = catalog
        self._outputs = outputs
        self._sandbox = sandbox
        self._settings = settings
        self._run_process = process_runner
        self._skill_read_grant = skill_read_grant

    def create(self, declaration, activation):
        return PythonSkillTool(
            declaration,
            activation,
            self._catalog,
            self._outputs,
            self._sandbox,
            self._settings,
            process_runner=self._run_process,
            skill_read_grant=self._skill_read_grant,
        )
