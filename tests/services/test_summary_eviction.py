from tests.services.test_summary import Generator
from tests.support.requests import TestTiming

from pycode.adapters.storage.session import JsonlSessionStore
from pycode.adapters.storage.summaries import FileSummaryStore
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.core.context_budget import InputBudget
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.models import ModelSelection
from pycode.core.session import SessionHeader
from pycode.core.summaries import SummarySettings
from pycode.core.tool_eviction import EvictionBudget
from pycode.core.tools import ToolContext
from pycode.services.context_projector import ContextProjector
from pycode.services.events import EventStream
from pycode.services.summary import SummaryService
from pycode.services.tool_eviction import ToolEvictionService


async def test_eviction_protects_generating_prefix_and_all_original_outputs_survive_adoption(
    tmp_path,
):
    directory = tmp_path / "sessions"
    history = JsonlSessionStore(
        directory / "s1", SessionHeader("s1", "/project", "t"), TestTiming()
    )
    outputs = FileToolOutputStore(directory, "s1", history)
    await history.append_message(Message("u1", "user", "initial"))
    references = []
    for index in range(6):
        call = f"c{index}"
        await history.append_message(
            Message(f"a{index}", "assistant", tool_calls=(ToolCall(call, "Read", {}),))
        )
        context = ToolContext("s1", "r1", call, "/project", call, "Read")
        result = await outputs.prepare(context, ToolResult(call, "Read", "x" * 14000), "head")
        entry = await history.append_message(Message(f"t{index}", "tool", tool_result=result))
        await outputs.bind(result.output_id, entry)
        references.append(result.output_id)
    await history.append_message(Message("u2", "user", "recent one"))
    await history.append_message(Message("u3", "user", "recent two"))
    projector = ContextProjector()
    generator = Generator(blocked=True)
    summary = SummaryService(
        history,
        projector,
        FileSummaryStore(tmp_path / "summaries"),
        generator,
        SummarySettings(
            ModelSelection("test", "model", "openai"), (), InputBudget.create(20000, 1000), 4000
        ),
        EventStream("s1", "r1", ()),
    )
    try:
        report = await summary.prepare(wait_for_capacity=False)
        await generator.started.wait()
        eviction = ToolEvictionService(
            history, projector, EvictionBudget(20000, protect_tokens=0, minimum_tokens=1)
        )
        cleanup = await eviction.apply(protected_entry_ids=report.protected_entry_ids)
        assert not set(cleanup.entry_ids).intersection(report.protected_entry_ids)
        generator.release.set()
        adopted = await summary.prepare(manual=True)
        assert adopted.applied_job_id == generator.sources[0].job_id
        for identifier in references:
            assert (await outputs.read(identifier, 1, 1, 32)).text == "x" * 32
    finally:
        await summary.aclose()
        await history.aclose()
