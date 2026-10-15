# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/types.ts
# Adaptation: Python dataclasses, separate tool-result role, nullable token fields, no prices.
"""Framework-independent completed messages and tool result contracts."""

from dataclasses import dataclass, field
from typing import Any, Literal

from pycode.kernel.contracts.model_identity import ModelSelection

Role = Literal["system", "user", "assistant", "tool", "custom"]
StopReason = Literal["stop", "tool_calls", "length", "error", "aborted", "content_filter"]
Purpose = Literal[
    "agent",
    "summary",
    "memory_extract",
    "memory_select",
    "tool_selection",
    "plan",
    "subagent",
    "retry",
]


@dataclass(frozen=True)
class ThinkingBlock:
    text: str
    signature: str | None = None
    redacted_data: str | None = None
    provider_data: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Attachment:
    media_type: str
    reference: str
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]
    raw_arguments: str | None = None
    parse_error: str | None = None


@dataclass(frozen=True)
class ToolResult:
    call_id: str
    name: str
    text: str
    is_error: bool = False
    error_code: str | None = None
    output_id: str | None = None
    exit_code: int | None = None
    structured_content: dict[str, Any] | None = None
    details: dict[str, Any] = field(default_factory=dict)
    terminate: bool = False
    duration_ms: float | None = None
    session_id: str | None = None
    run_id: str | None = None
    operation_id: str | None = None


@dataclass(frozen=True)
class UsageRecord:
    provider: str
    model: str
    request_id: str
    session_id: str = ""
    run_id: str = ""
    agent_id: str = "lead"
    purpose: Purpose = "agent"
    input_tokens: int | None = None
    output_tokens: int | None = None
    cache_read_tokens: int | None = None
    cache_miss_tokens: int | None = None
    cache_write_tokens: int | None = None
    reasoning_tokens: int | None = None
    total_tokens: int | None = None
    raw: dict[str, Any] = field(default_factory=dict)
    error: str | None = None


@dataclass(frozen=True)
class Message:
    id: str
    role: Role
    text: str = ""
    thinking: tuple[ThinkingBlock, ...] = ()
    attachments: tuple[Attachment, ...] = ()
    tool_calls: tuple[ToolCall, ...] = ()
    tool_result: ToolResult | None = None
    usage: UsageRecord | None = None
    stop_reason: StopReason | None = None
    timestamp: str = ""
    provider_metadata: dict[str, Any] = field(default_factory=dict)
    model_selection: ModelSelection | None = None
