import pytest
from langchain_core.messages import AIMessage, ToolMessage

from pycode.app.composition.bindings import build_agent_runtime
from pycode.context.services.context_projector import ContextProjector
from pycode.kernel.domain.history import SessionHeader
from pycode.kernel.domain.messages import Message, ToolResult
from pycode.kernel.domain.tools import ToolContext, ToolSpec
from pycode.kernel.domain.truncation import MAX_BYTES
from pycode.session.adapters.storage.header_reader import FileSessionHeaderReader
from pycode.session.adapters.storage.session import SqliteSessionStore
from pycode.tools.adapters.output_store import FileToolOutputStore, output_identifier
from pycode.tools.adapters.read_tool_result import ReadToolResult
from pycode.tools.adapters.schema import JsonSchemaValidator
from pycode.tools.adapters.tool_errors import execute_tool_errors
from pycode.tools.services.permissions import ReadOnlyToolGate
from pycode.tools.services.tool_executor import ToolExecutor
from pycode.tools.services.tool_registry import ToolRegistry
from tests.agent.adapters.test_agent import assemble
from tests.support.model import ScriptedChatModel
from tests.support.requests import TestTiming


async def test_large_tool_and_readback_run_through_actual_framework_and_same_executor(tmp_path):
    _, context, _, _, _, recorder = assemble([])
    store = SqliteSessionStore(
        tmp_path / context.session_id,
        SessionHeader(context.session_id, "/workspace", "t"),
        TestTiming(),
    )
    outputs = FileToolOutputStore(
        tmp_path, context.session_id, store, header_reader=FileSessionHeaderReader(tmp_path)
    )

    class BigOutput:
        spec = ToolSpec(
            "BigOutput", "Generate test output", {}, category="read", execution_mode="parallel"
        )

        async def execute(self, tool_context, arguments):
            return ToolResult("", "", "".join(f"line-{i:04d}\n" for i in range(1, 8001)))

    operation = ToolContext(
        context.session_id, context.run_id, f"{context.run_id}:a1:call-1", "/workspace"
    )
    identifier = output_identifier(operation)
    model = ScriptedChatModel(
        responses=[
            AIMessage(
                id="a1", content="", tool_calls=[{"id": "call-1", "name": "BigOutput", "args": {}}]
            ),
            AIMessage(
                id="a2",
                content="",
                tool_calls=[
                    {
                        "id": "call-2",
                        "name": "ReadToolResult",
                        "args": {
                            "output_id": identifier,
                            "offset": 500,
                            "limit": 100,
                        },
                    }
                ],
            ),
            AIMessage(id="a3", content="done"),
        ]
    )
    validator = JsonSchemaValidator()
    registry = ToolRegistry(validator, (BigOutput(), ReadToolResult(outputs)))
    context.registry = registry
    context.executor = ToolExecutor(
        registry,
        validator,
        ReadOnlyToolGate(),
        context.events,
        outputs,
        error_boundary=execute_tool_errors,
    )
    context.journal = store
    context.history_store = store
    context.projector = ContextProjector()
    context.outputs = outputs
    runtime = build_agent_runtime(model)
    result = await runtime.run((Message("u1", "user", "generate and read"),), context)
    assert result.final_text == "done"
    sent = [message for message in model.requests[1] if isinstance(message, ToolMessage)][0]
    assert len(sent.content.encode()) <= MAX_BYTES
    assert identifier in sent.content
    readback = [message for message in model.requests[2] if isinstance(message, ToolMessage)][-1]
    assert "line-0500" in readback.content and "line-0599" in readback.content
    assert "next_offset=600" in readback.content
    snapshot = await store.snapshot()
    results = [
        entry.message.tool_result
        for entry in snapshot.entries
        if entry.message and entry.message.tool_result
    ]
    assert results[1].output_id != results[0].output_id
    assert outputs.metadata(results[1].output_id).storage == "inline"
    assert sum(event.kind == "tool_execution_start" for event in recorder.events) == 2
    await store.aclose()


# This suite tests execution with tool definitions already disclosed.
pytestmark = pytest.mark.usefixtures("disclosed_tools")
