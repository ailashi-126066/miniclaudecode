"""One stable, bounded mapping for recoverable tool errors; storage defects stay exceptional."""

import re
from collections.abc import Mapping
from typing import Any

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
    if code == "hook_failed":
        details["critical_hook_failure"] = True
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
