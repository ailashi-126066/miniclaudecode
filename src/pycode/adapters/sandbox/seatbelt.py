# Source: MewCode local sandbox/seatbelt.py.
# SHA-256: b16d640a21d057388a858dd047302916167e49e08bafbc23c4b2f058750267af
# Adaptation: explicit argv, owned async probe, protected state and no launch fallback.
"""Seatbelt is enabled only after an actual executable probe succeeds."""

import asyncio
from pathlib import Path

from pycode.adapters.tools.process_runner import stop_process
from pycode.core.errors import ToolError
from pycode.core.sandbox import SandboxStatus
from pycode.core.values import json_dumps


def build_profile(workspace, network_enabled):
    root = Path(workspace).resolve()

    def quote(path):
        return json_dumps(str(path), ensure_ascii=False)

    rules = [
        "(version 1)",
        "(deny default)",
        "(allow process-exec)",
        "(allow process-fork)",
        "(allow sysctl-read)",
        '(allow file-read* (subpath "/"))',
        # Standard logging and redirected commands use the null device. This
        # grants no write access to an ordinary file outside the workspace.
        '(allow file-write-data (literal "/dev/null"))',
        f"(allow file-write* (subpath {quote(root)}))",
        f"(deny file-write-unlink (literal {quote(root)}))",
    ]
    for name in (".git", ".pycode", ".env"):
        path = quote(root / name)
        rules.append(f"(deny file-write* (subpath {path}) (literal {path}))")
    rules.append("(allow network*)" if network_enabled else "(deny network*)")
    return "\n".join(rules)


class UnavailableSandbox:
    async def status(self, settings):
        return SandboxStatus(False, False, False, "OS 沙箱不可用；使用权限审批")

    async def launch(self, argv, workspace, settings):
        return tuple(argv)


class SeatbeltSandbox:
    def __init__(self, executable="/usr/bin/sandbox-exec"):
        self._executable = executable

    async def usable(self):
        process = None
        spawning = asyncio.create_task(
            asyncio.create_subprocess_exec(
                self._executable,
                "-p",
                "(version 1)(allow default)",
                "/usr/bin/true",
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
                start_new_session=True,
            )
        )
        try:
            process = await asyncio.shield(spawning)
            async with asyncio.timeout(2):
                return await process.wait() == 0
        except asyncio.CancelledError:
            try:
                process = await spawning
            except OSError:
                pass
            if process is not None:
                await stop_process(process)
            raise
        except (OSError, TimeoutError):
            if process is not None:
                await stop_process(process)
            return False

    async def status(self, settings):
        available = await self.usable()
        enabled = available and settings.mode != "off"
        return SandboxStatus(
            available,
            enabled,
            enabled and settings.mode == "on-auto",
            "Seatbelt " + (settings.mode if enabled else "off")
            if available
            else "OS 沙箱不可用；使用权限审批",
        )

    async def launch(self, argv, workspace, settings):
        if settings.mode == "off":
            return tuple(argv)
        if not await self.usable():
            raise ToolError(
                "sandbox_unavailable", "Seatbelt became unavailable; command was not run"
            )
        profile = await asyncio.to_thread(build_profile, workspace, settings.network_enabled)
        return (self._executable, "-p", profile, *argv)
