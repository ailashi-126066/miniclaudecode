"""Grant a catalog-verified Skill root without exposing native sandbox adapters."""

from pathlib import Path
from typing import Protocol

from pycode.sandbox import SandboxBackend


class SkillReadGrant(Protocol):
    def __call__(self, sandbox: SandboxBackend, directory: Path) -> SandboxBackend: ...
