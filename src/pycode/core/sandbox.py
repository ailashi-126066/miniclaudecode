"""Command isolation settings are independent of ordinary permission approval."""

from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True)
class SandboxSettings:
    mode: Literal["off", "on", "on-auto"] = "off"
    network_enabled: bool = False

    def __post_init__(self):
        if self.mode not in {"off", "on", "on-auto"} or not isinstance(self.network_enabled, bool):
            raise ValueError("Invalid sandbox settings")


@dataclass(frozen=True)
class SandboxStatus:
    available: bool
    enabled: bool
    auto_approve: bool
    reason: str
