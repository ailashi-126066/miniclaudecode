from langchain_core.messages import AIMessage, ToolMessage
from tests.support.model import ScriptedChatModel
from tests.support.requests import TestTiming
from tests.workflows.test_agent import assemble

from pycode.adapters.storage.session import JsonlSessionStore
from pycode.adapters.tools.output_store import FileToolOutputStore, output_identifier
from pycode.adapters.tools.read_tool_result import ReadToolResult
from pycode.adapters.tools.schema import JsonSchemaValidator
from pycode.core.messages import Message, ToolResult
from pycode.core.session import SessionHeader
from pycode.core.tools import ToolContext, ToolSpec
from pycode.core.truncation import MAX_BYTES
from pycode.services.context_projector import ContextProjector
from pycode.services.permission import ReadOnlyToolGate
from pycode.services.tool_executor import ToolExecutor
from pycode.services.tool_registry import ToolRegistry
from pycode.workflows.agent import AgentRuntime


async def test_large_tool_and_readback_run_through_actual_framework_and_same_executor(tmp_path):
    _, context, _, _, _, recorder = assemble([])
    store = JsonlSessionStore(
        tmp_path / context.session_id,
        SessionHeader(context.session_id, "/workspace", "t"),
        TestTiming(),
    )
    outputs = FileToolOutputStore(tmp_path, context.session_id, store)

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
        registry, validator, ReadOnlyToolGate(), context.events, outputs
    )
    context.journal = store
    context.history_store = store
    context.projector = ContextProjector()
    context.outputs = outputs
    runtime = AgentRuntime(model, registry.list_specs())
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
