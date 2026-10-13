import json
from pathlib import Path

import pytest

from pycode.core.messages import ToolCall, ToolResult
from pycode.core.turn_policy import next_turn, tool_calls_may_execute

VECTORS = json.loads((Path(__file__).parents[1] / "fixtures/pi-agent-loop.json").read_text())


@pytest.mark.parametrize("case", VECTORS["cases"], ids=lambda case: case["name"])
def test_upstream_continuation_vectors(case):
    if "expected_execute" in case:
        assert tool_calls_may_execute(case["stop_reason"]) == case["expected_execute"]
        calls = tuple(ToolCall(**call) for call in case["calls"])
        results = tuple(
            ToolResult(call_id=call.id, name=call.name, text="output token limit", is_error=True)
            for call in calls
        )
        assert next_turn(case["stop_reason"], results).action == "request"
        return
    results = tuple(
        ToolResult(name="echo", text="ok", **result) for result in case.get("results", [])
    )
    decision = next_turn(
        stop_reason=case["stop_reason"],
        results=results,
        finish_action=case.get("finish_action"),
        steering_pending=case.get("steering_pending", False),
        follow_up_pending=case.get("follow_up_pending", False),
    )
    assert decision.action == case["expected_action"]
    if "expected_queue" in case:
        assert decision.queue == case["expected_queue"]
    if "expected_context_only" in case:
        assert decision.context_only == case["expected_context_only"]


@pytest.mark.parametrize("reason", ["error", "aborted", "content_filter"])
def test_failed_response_is_hard_exit_even_with_queued_inputs(reason):
    assert not tool_calls_may_execute(reason)
    assert next_turn(reason, (), "continue", True, True).action == "end"


def test_follow_up_is_selected_only_when_natural_work_would_end():
    result = ToolResult(call_id="t1", name="echo", text="ok")
    assert next_turn("tool_calls", (result,), follow_up_pending=True).queue is None
    assert next_turn("stop", (), follow_up_pending=True).queue == "follow_up"


def test_steer_cannot_enter_an_unsettled_batch():
    with pytest.raises(ValueError, match="settled"):
        next_turn("tool_calls", (), steering_pending=True, settled=False)
