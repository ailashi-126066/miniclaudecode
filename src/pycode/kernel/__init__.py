"""Public native contracts, policies and services for kernel."""

from pycode.kernel.contracts.completion import RunResult
from pycode.kernel.contracts.history import (
    EntryDraft,
    ProjectedContext,
    ProjectedEntry,
    SessionEntry,
    SessionHeader,
    SessionSnapshot,
)
from pycode.kernel.contracts.history_ports import HistoryJournal, MessageJournal
from pycode.kernel.contracts.messages import (
    Message,
    Purpose,
    StopReason,
    ThinkingBlock,
    ToolCall,
    ToolResult,
    UsageRecord,
)
from pycode.kernel.contracts.model_identity import ModelCapabilities, ModelSelection, ProviderKind
from pycode.kernel.contracts.tools import ExecutableTool, ExecutionMode, ToolContext, ToolSpec
from pycode.kernel.errors import (
    DomainError,
    ModelError,
    OutputClosed,
    SnapshotError,
    StorageError,
    ToolError,
)
from pycode.kernel.hashing import data_hash
from pycode.kernel.history_boundaries import valid_kept_boundary
from pycode.kernel.history_tree import SessionTree
from pycode.kernel.io.file_lease import FileLease
from pycode.kernel.io.path_io import atomic_write, file_sha256, io_path, owned_io
from pycode.kernel.io.timing import SystemTiming
from pycode.kernel.io.workspace_path import resolve_workspace_path
from pycode.kernel.path_policy import is_internal_component
from pycode.kernel.ports.events import EventConsumer, EventJournal, EventSink
from pycode.kernel.ports.timing import Timing
from pycode.kernel.redaction import SECRET_PATTERNS
from pycode.kernel.services.event_journal import MemoryEventJournal
from pycode.kernel.services.events import EventStream
from pycode.kernel.token_estimate import estimate_messages, estimate_text
from pycode.kernel.tool_pairing import repair_tool_pairing
from pycode.kernel.tool_text import model_tool_text
from pycode.kernel.truncation import MAX_BYTES, MAX_LINES, PreviewMode, line_count, truncate
from pycode.kernel.values import clone_value, freeze_value, json_dumps, plain_data

__all__ = [
    "DomainError",
    "EntryDraft",
    "EventConsumer",
    "EventJournal",
    "EventSink",
    "EventStream",
    "ExecutableTool",
    "ExecutionMode",
    "FileLease",
    "HistoryJournal",
    "MAX_BYTES",
    "MAX_LINES",
    "MemoryEventJournal",
    "Message",
    "MessageJournal",
    "ModelCapabilities",
    "ModelError",
    "ModelSelection",
    "OutputClosed",
    "PreviewMode",
    "ProjectedContext",
    "ProjectedEntry",
    "ProviderKind",
    "Purpose",
    "RunResult",
    "SECRET_PATTERNS",
    "SessionEntry",
    "SessionHeader",
    "SessionSnapshot",
    "SessionTree",
    "SnapshotError",
    "StopReason",
    "StorageError",
    "SystemTiming",
    "ThinkingBlock",
    "Timing",
    "ToolCall",
    "ToolContext",
    "ToolError",
    "ToolResult",
    "ToolSpec",
    "UsageRecord",
    "atomic_write",
    "clone_value",
    "data_hash",
    "estimate_messages",
    "estimate_text",
    "file_sha256",
    "freeze_value",
    "io_path",
    "is_internal_component",
    "json_dumps",
    "line_count",
    "model_tool_text",
    "owned_io",
    "plain_data",
    "repair_tool_pairing",
    "resolve_workspace_path",
    "truncate",
    "valid_kept_boundary",
]
