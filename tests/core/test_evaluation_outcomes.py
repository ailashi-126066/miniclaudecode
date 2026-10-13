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
