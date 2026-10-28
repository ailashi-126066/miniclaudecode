"""Task-owned live execution. No second durable store or polling of history files."""

import asyncio
from collections.abc import Callable
from dataclasses import dataclass, field

from pycode.team.contracts import ChildSession


@dataclass
class TaskExecution:
    session: ChildSession
    activity: asyncio.Event = field(default_factory=asyncio.Event)
    heartbeat: Callable[[], None] | None = None
    watcher: asyncio.Task | None = None
    sequences: dict[tuple[str, str, int], int] = field(default_factory=dict)
    failure_code: str | None = None
    source_versions: dict[str, str] = field(default_factory=dict)
