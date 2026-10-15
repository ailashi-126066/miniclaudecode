# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/agent-loop.ts
# Adaptation: pure decisions consumed by framework middleware, not a second execution loop.
"""Continuation decisions evaluated only after a fully settled turn."""

from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True)
class NextTurn:
    action: Literal["request", "end"]
    queue: Literal["steer", "follow_up"] | None = None


def next_turn(
    *,
    steering_pending: bool = False,
    follow_up_pending: bool = False,
) -> NextTurn:
    """Select queued input after the framework has completed the current Agent loop."""
    if steering_pending:
        return NextTurn("request", queue="steer")
    if follow_up_pending:
        return NextTurn("request", queue="follow_up")
    return NextTurn("end")
