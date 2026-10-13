# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/agent-session.ts
# Adaptation: at most three retries, SDK status classification and per-attempt records.
"""Pure request attempt records, classified failures and retry timing policy."""

import math
from dataclasses import dataclass, field
from typing import Any, Literal

from pycode.core.messages import Purpose, UsageRecord


@dataclass(frozen=True)
class ModelFailure:
    code: str
    message: str
    retryable: bool
    retry_after: float | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class RequestRecord:
    request_id: str
    session_id: str
    run_id: str
    provider: str
    model_id: str
    purpose: Purpose
    attempt: int
    status: Literal["started", "completed", "failed", "interrupted"]
    started_at: float
    agent_id: str = "lead"
    settled_at: float | None = None
    provider_request_id: str | None = None
    usage: UsageRecord | None = None
    error_code: str | None = None
    logical_id: str | None = None


@dataclass(frozen=True)
class RetryPolicy:
    max_retries: int = 3
    base_delay: float = 1
    max_delay: float = 60

    def __post_init__(self) -> None:
        if not 0 <= self.max_retries <= 3:
            raise ValueError("Model requests permit at most three retries")
        if not math.isfinite(self.base_delay) or not math.isfinite(self.max_delay):
            raise ValueError("Retry delays must be finite")
        if self.base_delay < 0 or self.max_delay < self.base_delay:
            raise ValueError("Invalid retry delay bounds")

    def delay(self, retry_number: int, retry_after: float | None = None) -> float:
        delay = min(self.base_delay * (2 ** (retry_number - 1)), self.max_delay)
        if retry_after is not None and math.isfinite(retry_after) and retry_after >= 0:
            delay = max(delay, retry_after)
        return delay
