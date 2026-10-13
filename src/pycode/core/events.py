# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/types.ts
# Adaptation: sequence and stable execution identifiers for common CLI/TUI events.
"""Unified event envelope; deltas are distinct from completed messages."""

from dataclasses import dataclass, field
from typing import Any, Literal

EventKind = Literal[
    "model_change",
    "model_request_start",
    "model_request_end",
    "agent_start",
    "agent_end",
    "turn_start",
    "turn_end",
    "message_start",
    "message_delta",
    "thinking_delta",
    "message_end",
    "tool_execution_start",
    "tool_output_ready",
    "tool_execution_update",
    "tool_execution_end",
    "usage",
    "retry",
    "needs_input",
    "summary",
    "notification",
    "error",
    "result",
]


@dataclass(frozen=True)
class Event:
    sequence: int
    session_id: str
    run_id: str
    kind: EventKind
    data: dict[str, Any] = field(default_factory=dict)
    operation_id: str | None = None
