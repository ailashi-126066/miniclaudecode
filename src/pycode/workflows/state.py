"""Deep Agents boundary extracted for sequential, readable ownership."""

import asyncio
from dataclasses import dataclass, field
from typing import Annotated, Any

from deepagents.graph import DeepAgentState

from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.models import ModelSelection
from pycode.core.tools import ToolSpec
from pycode.ports.events import EventSink
from pycode.ports.history import MessageJournal, SessionStore
from pycode.ports.outputs import ToolOutputStore
from pycode.services.context_projector import ContextProjector
from pycode.services.durable_inputs import DurableInputs
from pycode.services.input_queue import PendingInputs
from pycode.services.summary import SummaryService
from pycode.services.tool_eviction import ToolEvictionService
from pycode.services.tool_executor import ToolExecutor
from pycode.services.tool_registry import ToolRegistry
from pycode.workflows.model_requests import ModelRequestRunner


def latest_failure_field(previous, current):
    """Framework tool updates are reduced in their declared call order."""
    return current


class PycodeState(DeepAgentState):
    session_id: str
    run_id: str
    branch_leaf_id: str | None
    turn: int
    outcome: str
    final_text: str
    failure_tool: Annotated[str, latest_failure_field]
    failure_arguments: Annotated[str, latest_failure_field]
    failure_code: Annotated[str, latest_failure_field]
    failure_count: Annotated[int, latest_failure_field]


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
    inputs: PendingInputs
    requests: ModelRequestRunner
    initial_inputs: tuple[Message, ...] = ()
    execution_mode: str = "parallel"
    one_at_a_time: bool = False
    completed: list[Message] = field(default_factory=list)
    history_store: SessionStore | None = None
    projector: ContextProjector | None = None
    outputs: ToolOutputStore | None = None
    eviction: ToolEvictionService | None = None
    durable_inputs: DurableInputs | None = None
    agent_id: str = "lead"
    summary: SummaryService | None = None
    model_source_leaf_id: str | None = None
    skill_resources: Any = None
    skill_recorder: Any = None
    plans: Any = None
    team: Any = None
    workspace_files: Any = None
    model_tools: tuple[ToolSpec, ...] = ()
    system_hash: str = ""
    skill_selection: Any = None
    skill_boundary: bool = False
    model_fallback: Any = None
    tool_selector: Any = None
    selected_tool_names: tuple[str, ...] | None = None
    read_only: bool = False
    failure_guard: dict = field(default_factory=dict)
    serial_failure_signature: str | None = None
    interrupted_calls: set[str] = field(default_factory=set)
    duplicate_calls: asyncio.Condition = field(default_factory=asyncio.Condition)
    dispatched_calls: set[str] = field(default_factory=set)
    hooks: Any = None
    mcp: Any = None
    mcp_config_hash: str = ""
    assistant: Message | None = None
    call_order: list[str] = field(default_factory=list)
    ready_results: dict[str, tuple[ToolCall, ToolResult, dict]] = field(default_factory=dict)
    settled_calls: set[str] = field(default_factory=set)
    settlement: asyncio.Condition = field(default_factory=asyncio.Condition)
    turn_end_sent: bool = False
    tool_tasks: set[asyncio.Task] = field(default_factory=set)
