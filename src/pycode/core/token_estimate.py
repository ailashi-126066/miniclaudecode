# Source: https://github.com/anomalyco/opencode/blob/055d95bb7e278c94baf06235a52cac79dd13ba67/packages/core/src/util/token.ts
# Adaptation: explicit approximate token units; provider-reported usage is kept separately.
"""Stable conservative fallback estimates; never manufacture provider usage."""

import math

from pycode.core.messages import Message
from pycode.core.tool_text import model_tool_text
from pycode.core.values import json_dumps


def estimate_text(text: str) -> int:
    units = len(text) + sum(1 for character in text if ord(character) > 0xFFFF)
    return max(0, math.floor(units / 4 + 0.5))


def estimate_messages(messages: tuple[Message, ...]) -> int:
    total = 0
    for message in messages:
        total += 4
        total += estimate_text(message.text)
        for thinking in message.thinking:
            total += estimate_text(thinking.text)
        for call in message.tool_calls:
            total += estimate_text(
                call.name + json_dumps(call.arguments, ensure_ascii=False, sort_keys=True)
            )
        if message.tool_result is not None:
            total += estimate_text(model_tool_text(message.tool_result))
        total += 1024 * len(message.attachments)
    return total
