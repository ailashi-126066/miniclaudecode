"""Measured changes, verification and explicit claims determine evaluation outcomes."""

import pytest

from pycode.core.evaluation import evaluation_outcome


@pytest.mark.parametrize(
    "arguments,expected",
    [
        ({"tampered": True}, "forbidden_change"),
        ({"timeout": True}, "agent_timeout"),
        ({"changed": False}, "no_patch"),
        ({"verified": False}, "no_verification"),
        ({"failure_codes": ["repeated_tool_failure"]}, "repeated_tool_failure"),
        ({}, "passed"),
    ],
)
def test_evaluation_uses_each_required_boundary(arguments, expected):
    assert evaluation_outcome("completed", 0, **arguments) == expected


def test_claim_mismatch_requires_an_explicit_pass_claim_and_failed_independent_tests():
    assert evaluation_outcome("completed", 1, final_text="Tests passed") == "claim_mismatch"
    assert evaluation_outcome("completed", 1, final_text="Tests failed") == "tests_failed"


@pytest.mark.parametrize(
    "text",
    ["No test pass is claimed here", "I do not claim tests passed", "没有声明测试通过"],
)
def test_negated_pass_claim_is_not_reported_as_a_false_success(text):
    assert evaluation_outcome("completed", 1, verified=False, final_text=text) == "no_verification"


def test_separate_positive_claim_is_not_hidden_by_a_previous_negative_clause():
    assert (
        evaluation_outcome(
            "completed",
            1,
            final_text="No tests passed initially, but all tests passed after the fix.",
        )
        == "claim_mismatch"
    )
    assert (
        evaluation_outcome("completed", 1, final_text="No errors occurred and all tests passed.")
        == "claim_mismatch"
    )
