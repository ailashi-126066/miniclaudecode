"""Consumer-owned execution capabilities; no session or framework implementation types."""

from typing import Literal, Protocol

from pycode.kernel import Message, ModelSelection, ProjectedContext, SessionSnapshot, ToolResult
from pycode.models import ToolSelectorSettings


class InputQueue(Protocol):
    def has(self, kind: str) -> bool: ...
    def take(self, kind: str, *, one_at_a_time: bool = False) -> tuple[Message, ...]: ...


class DurableInputDelivery(Protocol):
    async def submit(
        self, message: Message, kind: Literal["steer", "follow_up"], run_id: str
    ) -> bool: ...
    async def pending(self, run_id: str) -> InputQueue: ...
    async def finish_boundary(self, run_id: str) -> InputQueue: ...


class HistoryProjector(Protocol):
    def project(self, snapshot: SessionSnapshot) -> ProjectedContext: ...


class RequestTracking(Protocol):
    active_request_id: str | None
    selection: ModelSelection


class TeamMailbox(Protocol):
    async def flush_inbox(self, context: object) -> None: ...
    async def settle_tool(self, result: ToolResult | str, *, accepted: bool) -> None: ...


class TitleAssignment(Protocol):
    async def assign(self, message: Message) -> None: ...


class WorkspaceReadback(Protocol):
    async def offload_read(self, context, arguments, outputs) -> ToolResult: ...


class FallbackControl(Protocol):
    def restore_primary(self, context: object) -> None: ...
    async def aclose(self) -> None: ...


class SelectorSettingsView(Protocol):
    settings: ToolSelectorSettings
