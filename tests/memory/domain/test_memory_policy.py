"""Pure memory entry normalization and write-filter policy."""

import pytest

from pycode.kernel.domain.errors import ToolError
from pycode.memory.domain.memory import (
    MAX_ENTRY_CHARS,
    check_memory_write,
    memory_record,
    memory_text,
    normalize_value,
)


def test_normalize_langmem_tool_and_store_manager_values():
    assert normalize_value({"content": "Prefers tabs."}) == {
        "kind": "Memory",
        "content": {"content": "Prefers tabs."},
    }
    manager = {"kind": "Memory", "content": {"content": "Uses uv."}}
    assert normalize_value(manager) == manager
    assert normalize_value({"content": manager}) == manager
    with pytest.raises(ToolError):
        normalize_value("plain text")


def test_memory_text_displays_current_text_and_generic_structured_content():
    assert memory_text({"kind": "Memory", "content": {"content": "A"}}) == "A"
    assert memory_text({"kind": "Custom", "content": {"x": 1}}) == '{"x": 1}'


@pytest.mark.parametrize(
    "secret",
    [
        "sk-ABCDEFGHIJKLMNOP1234",
        "sk-proj-ABCDEFGHIJKLMN_12345678",
        '"api_key": "hunter2"',
        "my api key = value",
        "my api-key: something",
        "Secret = value",
        "github token:abc",
        "password: hunter2",
        "AKIAIOSFODNN7EXAMPLE",
        "aws_secret_access_key is set",
    ],
)
def test_secret_like_content_is_rejected(secret):
    with pytest.raises(ToolError) as error:
        check_memory_write(normalize_value({"content": secret}), 1)
    assert error.value.code == "memory_secret_rejected"


def test_length_and_count_limits():
    check_memory_write(normalize_value({"content": "x" * MAX_ENTRY_CHARS}), 1)
    with pytest.raises(ToolError) as error:
        check_memory_write(normalize_value({"content": "x" * (MAX_ENTRY_CHARS + 1)}), 1)
    assert error.value.code == "memory_entry_too_long"
    with pytest.raises(ToolError) as error:
        check_memory_write(normalize_value({"content": "ok"}), 10_000)
    assert error.value.code == "memory_limit_reached"
    check_memory_write(normalize_value({"content": "The token budget is 4000 tokens"}), 1)


def test_memory_record_projection():
    record = {
        "value": {"kind": "Memory", "content": {"content": "Prefers pytest."}},
        "created_at": "2026-01-01T00:00:00+00:00",
        "updated_at": "2026-01-02T00:00:00+00:00",
    }
    projected = memory_record(["memories", "user", "u"], "k", record, "user")
    assert projected.namespace == ("memories", "user", "u")
    assert projected.memory_id == "k" and projected.scope == "user"
    assert projected.content == "Prefers pytest." and projected.kind == "Memory"


@pytest.mark.parametrize("field", ["extra", "body", "title"])
def test_length_limit_includes_fields_outside_the_display_text(field):
    value = {"kind": "Memory", "content": {"content": "short", field: "x" * MAX_ENTRY_CHARS}}
    with pytest.raises(ToolError) as error:
        check_memory_write(normalize_value(value), 1)
    assert error.value.code == "memory_entry_too_long"


def test_secret_filter_inspects_json_escaped_nested_metadata():
    value = {"kind": "Memory", "content": {"content": "short", "other": '"api_key": "hunter2"'}}
    with pytest.raises(ToolError) as error:
        check_memory_write(normalize_value(value), 1)
    assert error.value.code == "memory_secret_rejected"
