# Source: MewCode local mewcode/context/summary_cache.py; SHA-256:
# e291f27c5264240e6a535b4ec997e0958cec665f5be1e36f0f9f5f7b08621db0
# Adaptation: explicit validated reserve; no client inspection, filesystem or network access.
"""Input capacity and the early background-summary trigger are different thresholds."""

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class InputBudget:
    window: int
    output_reserve: int
    safety: int

    def __post_init__(self):
        if self.window <= 0 or self.output_reserve < 1 or self.safety < 0:
            raise ValueError("Window, output reserve and safety are invalid")
        if self.output_reserve + self.safety >= self.window:
            raise ValueError("Output reserve and safety exhaust the input window")

    @classmethod
    def create(cls, window: int, output_reserve: int):
        return cls(window, output_reserve, max(64, math.ceil(window * 0.005)))

    @property
    def input_limit(self) -> int:
        return self.window - self.output_reserve - self.safety

    @property
    def cache_trigger(self) -> int:
        return min(
            math.ceil(self.window * 0.80),
            max(1, self.input_limit - max(64, math.ceil(self.window * 0.02))),
        )
