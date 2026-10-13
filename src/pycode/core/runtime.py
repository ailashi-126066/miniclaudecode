"""Completed execution result, separate from individual model turns."""

from dataclasses import dataclass
from typing import Any

from pycode.core.messages import Message


@dataclass(frozen=True)
class RunResult:
    outcome: str
    final_text: str
    messages: tuple[Message, ...]
    pending: tuple[dict[str, Any], ...] = ()
    checkpoint_id: str | None = None
    run_id: str | None = None
