"""One stable, bounded mapping for recoverable tool errors; storage defects stay exceptional."""

import re
from collections.abc import Mapping
from typing import Any, TypedDict

from pycode.kernel import SECRET_PATTERNS, ToolError, ToolResult


def tool_failure(error, call, *, executed=False, uncertain=False):
    if isinstance(error, ToolError):
        code = error.code
        if not isinstance(code, str) or not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", code):
            code = "tool_execution_error"
        text = str(error)
    elif isinstance(error, OSError):
        code = "tool_io_error"
        text = "Tool I/O operation failed"
    else:
        raise error
    for pattern in SECRET_PATTERNS:
        text = re.sub(pattern, "[redacted]", text)
    details = {"executed": executed}
    if uncertain:
        details["side_effects_uncertain"] = True
    return ToolResult(
        call.id, call.name, text[:1600], is_error=True, error_code=code, details=details
    )


def repeated_tool_failure(guard: Mapping[str, Any], name: str, arguments_hash: str) -> bool:
    """Reject a fourth execution of the same consecutively failing declaration."""
    return (
        guard.get("failure_count", 0) >= 3
        and guard.get("failure_tool") == name
        and guard.get("failure_arguments") == arguments_hash
    )


class FailureGuard(TypedDict, total=False):
    failure_tool: str
    failure_arguments: str
    failure_code: str
    failure_count: int


def failure_update(previous, call, result, arguments_hash) -> FailureGuard:
    count = 0
    if result.is_error:
        same = (
            previous.get("failure_tool") == call.name
            and previous.get("failure_arguments") == arguments_hash
            and previous.get("failure_code") == result.error_code
        )
        count = previous.get("failure_count", 0) + 1 if same else 1
    repeated = result.error_code == "repeated_tool_failure"
    return {
        "failure_tool": call.name if result.is_error else "",
        "failure_arguments": arguments_hash if result.is_error else "",
        "failure_code": previous.get("failure_code", "") if repeated else result.error_code or "",
        "failure_count": previous.get("failure_count", 3) if repeated else count,
    }
