# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/agent-loop.ts
# Adaptation: pure decisions consumed by framework middleware, not a second execution loop.
"""Continuation decisions evaluated only after a fully settled turn."""

from dataclasses import dataclass
from typing import Literal

from pycode.core.messages import StopReason, ToolResult


@dataclass(frozen=True)
class NextTurn:
    action: Literal["request", "end"]
    queue: Literal["steer", "follow_up"] | None = None
    context_only: bool = False


def tool_calls_may_execute(stop_reason: StopReason | None) -> bool:
    return stop_reason not in {"length", "error", "aborted", "content_filter"}


def next_turn(
    stop_reason: StopReason | None,
    results: tuple[ToolResult, ...],
    finish_action: Literal["continue", "end"] | None = None,
    steering_pending: bool = False,
    follow_up_pending: bool = False,
    *,
    settled: bool = True,
) -> NextTurn:
    if not settled:
        raise ValueError("Continuation requires a settled model and tool batch")
    if stop_reason in {"error", "aborted", "content_filter"} or finish_action == "end":
        return NextTurn("end")
    natural_tool_turn = bool(results) and not all(result.terminate for result in results)
    if steering_pending:
        return NextTurn("request", queue="steer")
    if natural_tool_turn:
        return NextTurn("request")
    if follow_up_pending:
        return NextTurn("request", queue="follow_up")
    if finish_action == "continue":
        return NextTurn("request", context_only=True)
    return NextTurn("end")
