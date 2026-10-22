"""Hooks types: component-owned implementation."""

# Source: MewCode local hooks/conditions.py and models.py; fixed SHA in docs/source-map.md.
# SHA-256 conditions: d11485198df8f4ebf76a009c4b13eaf51883ae0a1bbc14f734779d684817f870
# SHA-256 models: 0a07d8f977875f523398ca5972eb11c1be0a074df7045711e758f92bfb416e19
# Adaptation: immutable native records, validated conditions and stable boundary identities.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
import re
from dataclasses import dataclass, field

from pycode.kernel import DomainError

HOOK_EVENTS = frozenset(
    {
        "startup",
        "shutdown",
        "session_start",
        "session_end",
        "turn_start",
        "turn_end",
        "pre_send",
        "post_receive",
        "pre_tool_use",
        "post_tool_use",
        "compact",
        "permission_request",
        "file_change",
        "command_execute",
        "error",
    }
)


@dataclass(frozen=True)
class HookCondition:
    field: str
    operator: str
    value: str


@dataclass(frozen=True)
class HookConditionGroup:
    conditions: tuple[HookCondition, ...]
    logic: str = "and"


def parse_condition(expression):
    if not expression.strip():
        return None
    if "&&" in expression and "||" in expression:
        raise DomainError("invalid_hook_condition", "Cannot mix && and || in a condition")
    logic = "or" if "||" in expression else "and"
    parts = expression.split("||" if logic == "or" else "&&")
    conditions = []
    for part in parts:
        for operator in ("==", "!=", "=~", "~="):
            if operator not in part:
                continue
            key, value = (item.strip() for item in part.split(operator, 1))
            if not key or not value:
                raise DomainError("invalid_hook_condition", "Condition needs a field and value")
            if value.startswith('"') and value.endswith('"'):
                value = value[1:-1]
            conditions.append(HookCondition(key, operator, value))
            break
        else:
            raise DomainError("invalid_hook_condition", "No valid operator in condition")
    return HookConditionGroup(tuple(conditions), logic)


@dataclass(frozen=True)
class HookAction:
    type: str
    command: str = ""
    message: str = ""
    timeout: float = 30


@dataclass(frozen=True)
class Hook:
    id: str
    event: str
    action: HookAction
    condition: HookConditionGroup | None = None
    reject: bool = False
    once: bool = False
    source: str = "user"


@dataclass(frozen=True)
class HookContext:
    session_id: str
    run_id: str
    workspace: str
    boundary_id: str
    event_name: str
    tool_name: str = ""
    tool_args: dict = field(default_factory=dict)
    file_path: str = ""
    message: str = ""
    error: str = ""

    def fields(self):
        return {
            "tool": self.tool_name,
            "event": self.event_name,
            "file": self.file_path,
            "message": self.message,
            "error": self.error,
            **{"args." + key: str(value) if value else "" for key, value in self.tool_args.items()},
        }

    def expand(self, template):
        values = {
            "EVENT": self.event_name,
            "TOOL_NAME": self.tool_name,
            "FILE_PATH": self.file_path,
            "MESSAGE": self.message,
            "ERROR": self.error,
            **{"TOOL_ARGS." + key: str(value) for key, value in self.tool_args.items()},
        }
        pattern = r"\$(TOOL_ARGS\.[A-Za-z0-9_-]+|EVENT|TOOL_NAME|FILE_PATH|MESSAGE|ERROR)\b"
        return re.sub(pattern, lambda match: values.get(match.group(1), match.group(0)), template)
