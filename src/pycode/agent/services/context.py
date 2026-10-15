"""Framework-free per-run execution state bound to consumer-owned capabilities."""

import asyncio
from dataclasses import dataclass, field

from pycode.agent.ports import (
    DurableInputDelivery,
    FallbackControl,
    HistoryProjector,
    InputQueue,
    RequestTracking,
    SelectorSettingsView,
    TeamMailbox,
    WorkspaceReadback,
)
from pycode.context import SummaryService, ToolEvictionService
from pycode.hooks import HookEngine
from pycode.kernel import (
    EventSink,
    HistoryJournal,
    Message,
    MessageJournal,
    ModelSelection,
    ToolCall,
    ToolResult,
    ToolSpec,
)
from pycode.plans import PlanService
from pycode.tools import ToolExecutor, ToolOutputStore, ToolRegistry


@dataclass
class RunContext:
    session_id: str
    run_id: str
    workspace: str
    selection: ModelSelection
    events: EventSink
    journal: MessageJournal
    executor: ToolExecutor
    registry: ToolRegistry
    inputs: InputQueue
    requests: RequestTracking
    initial_inputs: tuple[Message, ...] = ()
    execution_mode: str = "parallel"
    one_at_a_time: bool = False
    completed: list[Message] = field(default_factory=list)
    history_store: HistoryJournal | None = None
    projector: HistoryProjector | None = None
    outputs: ToolOutputStore | None = None
    eviction: ToolEvictionService | None = None
    durable_inputs: DurableInputDelivery | None = None
    agent_id: str = "lead"
    summary: SummaryService | None = None
    model_source_leaf_id: str | None = None
    plans: PlanService | None = None
    workspace_files: WorkspaceReadback | None = None
    model_tools: tuple[ToolSpec, ...] = ()
    system_hash: str = ""
    model_fallback: FallbackControl | None = None
    tool_selector: SelectorSettingsView | None = None
    selected_tool_names: tuple[str, ...] | None = None
    read_only: bool = False
    failure_guard: dict = field(default_factory=dict)
    serial_failure_signature: str | None = None
    interrupted_calls: set[str] = field(default_factory=set)
    duplicate_calls: asyncio.Condition = field(default_factory=asyncio.Condition)
    dispatched_calls: set[str] = field(default_factory=set)
    team: TeamMailbox | None = None
    hooks: HookEngine | None = None
    mcp_config_hash: str = ""
    assistant: Message | None = None
    call_order: list[str] = field(default_factory=list)
    ready_results: dict[str, tuple[ToolCall, ToolResult, dict]] = field(default_factory=dict)
    settled_calls: set[str] = field(default_factory=set)
    settlement: asyncio.Condition = field(default_factory=asyncio.Condition)
    turn_end_sent: bool = False
    tool_tasks: set[asyncio.Task] = field(default_factory=set)
