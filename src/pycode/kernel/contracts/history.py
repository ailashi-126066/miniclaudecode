# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/session-manager.ts
# Adaptation: native Python schema and immutable data; no framework message classes.
"""Completed session history, active position and projection contracts."""

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any, Literal

from pycode.kernel.contracts.messages import Message
from pycode.kernel.contracts.model_identity import ModelSelection


@dataclass(frozen=True)
class SessionHeader:
    session_id: str
    workspace: str
    created_at: str
    application: Literal["pycode"] = "pycode"
    schema_version: int = 1
    parent_session_id: str | None = None
    forked_from_entry_id: str | None = None


@dataclass(frozen=True)
class SessionEntry:
    id: str
    parent_id: str | None
    type: str
    timestamp: str
    data: dict[str, Any] = field(default_factory=dict)
    message: Message | None = None
    selection: ModelSelection | None = None
    run_id: str | None = None
    operation_id: str | None = None


@dataclass(frozen=True)
class EntryDraft:
    type: str
    data: dict[str, Any] = field(default_factory=dict)
    message: Message | None = None
    selection: ModelSelection | None = None
    id: str | None = None
    run_id: str | None = None
    operation_id: str | None = None


@dataclass(frozen=True)
class SessionSnapshot:
    header: SessionHeader
    entries: tuple[SessionEntry, ...]
    leaf_id: str | None
    revision: int
    log_offset: int
    issues: tuple[str, ...] = ()
    entries_by_id: Mapping[str, SessionEntry] | None = field(
        default=None, compare=False, repr=False, metadata={"serialize": False}
    )
    active_path_ids: tuple[str, ...] = ()

    @property
    def index(self):
        if self.entries_by_id is not None:
            if not self.entries or self.entries_by_id.get(self.entries[-1].id) is self.entries[-1]:
                return self.entries_by_id
        return self.entries


@dataclass(frozen=True)
class ProjectedEntry:
    entry_id: str
    messages: tuple[Message, ...]


@dataclass(frozen=True)
class ProjectedContext:
    leaf_id: str | None
    entries: tuple[ProjectedEntry, ...]
    messages: tuple[Message, ...]
    selection: ModelSelection | None
    thinking: str
    checkpoint: dict[str, Any] = field(default_factory=dict)
