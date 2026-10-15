import json
from dataclasses import asdict
from pathlib import Path

import pytest

from pycode.adapters.hooks.config import parse_hooks
from pycode.core.errors import DomainError
from pycode.core.hooks import HOOK_EVENTS, HookContext, parse_condition

VECTORS = json.loads(
    (Path(__file__).parents[2] / "fixtures/hook-baselines.json").read_text(encoding="utf-8")
)


def test_fixed_event_and_condition_vectors_parse_before_execution():
    assert HOOK_EVENTS == set(VECTORS["events"])
    for expression, _, _ in VECTORS["conditions"]:
        assert parse_condition(expression).conditions
    for expression in VECTORS["invalid_conditions"]:
        with pytest.raises(DomainError):
            parse_condition(expression)
    assert parse_condition("") is None
    hook = parse_hooks([VECTORS["config"]])[0]
    assert hook.reject and hook.condition.conditions[0].field == "tool"
    context = HookContext(
        "s",
        "r",
        "/workspace",
        "b",
        "post_tool_use",
        "Write",
        {"file_path": "src/main.py"},
        "src/main.py",
        "done",
    )
    assert (
        context.expand(VECTORS["template"])
        == "Event=post_tool_use Tool=Write File=src/main.py Msg=done Err= Arg=src/main.py"
    )
    assert asdict(context)["boundary_id"] == "b"


@pytest.mark.parametrize(
    "change",
    [
        {"async": True},
        {"event": "invalid"},
        {"reject": "yes"},
        {"action": []},
        {"action": {"type": "http", "url": "https://hooks.example.test"}},
    ],
)
def test_invalid_configuration_is_a_domain_error(change):
    with pytest.raises(DomainError):
        parse_hooks([{**VECTORS["config"], **change}])
