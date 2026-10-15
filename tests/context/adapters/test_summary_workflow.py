import pytest
from langchain_core.messages import AIMessage

from pycode.context.adapters.summary import SUMMARY_SYSTEM, serialize_source
from pycode.context.module import ContextModule
from pycode.kernel.domain.errors import ModelError
from pycode.kernel.services.events import EventStream
from pycode.models.adapters.failures import ProviderFailureClassifier
from pycode.models.adapters.model_requests import ModelRequestRunner
from pycode.models.domain.requests import RetryPolicy
from pycode.models.module import ModelsModule
from tests.context.services.test_summary import Generator, setup
from tests.support.model import ScriptedChatModel
from tests.support.requests import MemoryRequestStore, TestTiming


@pytest.mark.parametrize(
    "case",
    [
        "valid",
        "valid-tags",
        "content-blocks",
        "empty",
        "empty-tags",
        "analysis-only",
        "length",
        "tool",
    ],
)
async def test_summary_is_a_real_single_framework_request_with_usage_and_no_tool_execution(
    tmp_path, case
):
    history, _, settings, _, service = await setup(tmp_path, Generator())
    try:
        await service.prepare()
        await service.collect(cancel=False)
        source = next(
            entry.data["job_id"]
            for entry in (await history.snapshot()).entries
            if entry.data.get("kind") == "summary_job"
        )
        from pycode.context.adapters.storage.summaries import FileSummaryStore

        captured = (await FileSummaryStore(tmp_path / "summaries").load(source))[0]
        response = AIMessage(
            content="Goal: continue the task",
            usage_metadata={
                "input_tokens": 100,
                "output_tokens": 20,
                "total_tokens": 120,
            },
        )
        if case == "valid-tags":
            response = response.model_copy(
                update={"content": "<summary>Goal: continue the task</summary>"}
            )
        elif case == "content-blocks":
            response = response.model_copy(
                update={
                    "content": [
                        {"type": "thinking", "thinking": "private reasoning"},
                        {"type": "text", "text": "Goal: continue the task"},
                    ]
                }
            )
        elif case == "empty":
            response = AIMessage(content="")
        elif case == "empty-tags":
            response = AIMessage(content="<summary> </summary>")
        elif case == "analysis-only":
            response = AIMessage(content="<analysis>Unfinished reasoning</analysis>")
        elif case == "length":
            response = AIMessage(content="partial", response_metadata={"finish_reason": "length"})
        elif case == "tool":
            response = AIMessage(
                content="",
                tool_calls=[{"id": "c1", "name": "Bash", "args": {"command": "must-not-run"}}],
            )
        model = ScriptedChatModel(responses=[response])
        context = ContextModule(
            request_step=ModelsModule.model_request_step, stop_reason=ModelsModule.stop_reason
        )
        records = MemoryRequestStore()
        timing = TestTiming()
        runner = ModelRequestRunner(
            records,
            ProviderFailureClassifier(timing),
            timing,
            RetryPolicy(),
            EventStream("s1", captured.job_id, ()),
            settings.selection,
            "s1",
            captured.job_id,
            purpose="summary",
        )
        if case in {"valid", "valid-tags", "content-blocks"}:
            completion = await context.workflow(model).run(captured, runner)
            assert completion.text == "Goal: continue the task"
            assert (
                completion.usage[0].purpose == "summary" and completion.usage[0].total_tokens == 120
            )
        else:
            with pytest.raises(ModelError):
                await context.workflow(model).run(captured, runner)
        assert len(model.requests) == 1
        assert model.requests[0][0].content == SUMMARY_SYSTEM
        assert model.requests[0][1].content == serialize_source(captured)
        assert "<conversation>" in model.requests[0][1].content
        assert (await records.list_requests())[0].purpose == "summary"
    finally:
        await service.aclose()
        await history.aclose()
