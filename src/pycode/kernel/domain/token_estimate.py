# Source: https://github.com/anomalyco/opencode/blob/055d95bb7e278c94baf06235a52cac79dd13ba67/packages/core/src/util/token.ts
# Adaptation: explicit approximate token units; provider-reported usage is kept separately.
"""Stable conservative fallback estimates; never manufacture provider usage."""

import math


def estimate_text(text: str) -> int:
    units = len(text) + sum(1 for character in text if ord(character) > 0xFFFF)
    return max(0, math.floor(units / 4 + 0.5))
