"""A native read preserves a giant requested line and resumes its UTF-8 bytes."""

from deepagents.backends import CompositeBackend, StateBackend
from langchain_core.messages import AIMessage
from tests.adapters.tools.test_output_store import setup
from tests.workflows.test_agent import assemble

from pycode.adapters.tools.artifacts_backend import ToolArtifactsBackend
from pycode.adapters.tools.backend import PycodeBackend
from pycode.core.messages import Message
from pycode.core.values import plain_data
from pycode.services.context_projector import ContextProjector


async def test_native_giant_line_has_one_complete_object_and_bounded_continuation(tmp_path):
    giant = "€" * 50000 + "\n"
    source = tmp_path / "giant.txt"
    source.write_bytes((giant + "unrequested line\n").encode())
    store, outputs, _ = await setup(tmp_path)
    runtime, context, model, _, _, _ = assemble(
        [
            AIMessage(
                id="read",
                content="",
                tool_calls=[
                    {
                        "id": "c1",
                        "name": "read_file",
                        "args": {"file_path": "/workspace/giant.txt", "limit": 1},
                    }
                ],
            ),
            AIMessage(id="done", content="done"),
        ]
    )
    context.session_id = "s1"
    context.workspace = str(tmp_path)
    context.journal = store
    context.history_store = store
    context.projector = ContextProjector()
    context.outputs = outputs
    context.executor.outputs = outputs
    files = PycodeBackend(tmp_path)
    context.workspace_files = files
    objects = ToolArtifactsBackend(outputs, store)
    runtime.backend = CompositeBackend(
        default=StateBackend(),
        routes={
            "/workspace/": files,
            "/large_tool_results/": objects,
        },
    )
    try:
        result = await runtime.run((Message("u2", "user", "read one giant line"),), context)
        assert result.outcome == "completed" and model.call_index == 2
        tool_result = next(
            message.tool_result for message in result.messages if message.tool_result
        )
        assert len(tool_result.text.encode()) <= 50000 and tool_result.output_id
        assert not tool_result.is_error and "ReadToolResult" in tool_result.text
        parts = []
        offset, byte_offset = 1, 0
        while True:
            page = await outputs.read(tool_result.output_id, offset, 1, 4096, byte_offset)
            assert len(page.text.encode()) <= 4096 and "�" not in page.text
            parts.append(page.text)
            if not page.has_more:
                break
            offset, byte_offset = page.next_offset, page.next_byte_offset
        assert "".join(parts) == giant
        native_page = await objects.aread("/" + tool_result.output_id + ".txt", 0, 1)
        assert native_page.error is None and "byte_offset=" in native_page.file_data["content"]
        metadata = plain_data(outputs.metadata(tool_result.output_id))
        assert metadata["preview"] == "" and "settled_result" not in metadata
        assert (await objects.awrite("/" + tool_result.output_id + ".txt", "bad")).error
        await store.branch(None)
        denied = await objects.aread("/" + tool_result.output_id + ".txt")
        assert denied.error.startswith("output_not_accessible")
    finally:
        await store.aclose()
