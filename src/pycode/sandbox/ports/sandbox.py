"""Replaceable native command-isolation boundary."""

from typing import Protocol

from pycode.sandbox.domain.sandbox import SandboxSettings, SandboxStatus


class SandboxBackend(Protocol):
    async def status(self, settings: SandboxSettings) -> SandboxStatus: ...

    async def launch(
        self, argv: tuple[str, ...], workspace: str, settings: SandboxSettings
    ) -> tuple[str, ...]: ...
