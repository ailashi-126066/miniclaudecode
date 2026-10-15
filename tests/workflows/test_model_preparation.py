"""Model preparation order protects sources and delivers steering only after maintenance."""

import asyncio
from dataclasses import replace
from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage
from pydantic import Field
from tests.support.model import ScriptedChatModel
from tests.support.requests import TestTiming
from tests.workflows.test_agent import Journal, assemble

from pycode.adapters.storage.session import JsonlSessionStore
from pycode.core.errors import ModelError
from pycode.core.messages import Message
from pycode.core.session import SessionHeader
from pycode.core.summaries import SummaryReport
from pycode.services.context_projector import ContextProjector
from pycode.workflows.agent import AgentRuntime
from pycode.workflows.history import HistoryMiddleware


class MCP:
    def __init__(self, trace):
        self.trace = trace
        self.started = asyncio.Event()
        self.blocked = False
        self.failure = None

    async def refresh(self, registry):
        self.trace.append("refresh")
        self.started.set()
        if self.failure is not None:
            raise self.failure
        if self.blocked:
            await asyncio.Event().wait()


class Summary:
    def __init__(self, trace):
        self.trace = trace

    def set_tools(self, tools):
        assert tools
        self.trace.append("tools")

    async def prepare(self):
        self.trace.append("summary")
        return SummaryReport(1, "cached", ("protected-source",), "applied")


class Eviction:
    def __init__(self, trace):
        self.trace = trace

    async def apply(self, *, protected_entry_ids):
        assert protected_entry_ids == ("protected-source",)
        self.trace.append("eviction")


class Hooks:
    def __init__(self, trace):
        self.trace = trace

    async def fire(self, context):
        self.trace.append(context.event_name)


class SteeringJournal(Journal):
    def __init__(self, trace):
        super().__init__()
        self.trace = trace

    async def append_message(self, message):
        self.trace.append("steering")
        return await super().append_message(message)


class Team:
    def __init__(self, trace):
        self.trace = trace

    async def tasks(self):
        self.trace.append("team")
        return []


def preparation():
    trace = []
    _, context, _, _, _, _ = assemble([])
    context.mcp = MCP(trace)
    context.summary = Summary(trace)
    context.eviction = Eviction(trace)
    context.hooks = Hooks(trace)
    context.team = Team(trace)
    context.journal = SteeringJournal(trace)
    context.inputs.submit(Message("steer", "user", "new instruction"), "steer")
    return context, trace


async def test_preparation_keeps_refresh_summary_protection_steering_and_turn_order():
    context, trace = preparation()
    updates = await HistoryMiddleware().abefore_model({"turn": 1}, SimpleNamespace(context=context))
    assert trace == [
        "refresh",
        "tools",
        "summary",
        "compact",
        "eviction",
        "steering",
        "turn_start",
        "team",
    ]
    assert updates["turn"] == 2
    assert updates["async_tasks"] == []
    assert len(updates["messages"]) == 1
    assert updates["messages"][0].content == "new instruction"
    assert len(context.journal.messages) == 1
    assert not context.inputs.has("steer")


@pytest.mark.parametrize("cancel", [False, True])
async def test_failed_or_cancelled_refresh_does_not_execute_dependent_steps(cancel):
    context, trace = preparation()
    context.mcp.blocked = cancel
    if not cancel:
        context.mcp.failure = ModelError("refresh_failed", "test")
    running = asyncio.create_task(
        HistoryMiddleware().abefore_model({"turn": 0}, SimpleNamespace(context=context))
    )
    try:
        await asyncio.wait_for(context.mcp.started.wait(), 5)
        if cancel:
            running.cancel()
        with pytest.raises(asyncio.CancelledError if cancel else ModelError):
            await running
        assert trace == ["refresh"]
        assert context.inputs.has("steer")
        assert context.journal.messages == []
    finally:
        if not running.done():
            running.cancel()
            await asyncio.gather(running, return_exceptions=True)


class ToolCapturingModel(ScriptedChatModel):
    declared_tools: list = Field(default_factory=list)

    def bind_tools(self, tools, **options):
        names = []
        for tool in tools:
            if isinstance(tool, dict):
                names.append(tool.get("function", tool).get("name"))
            else:
                names.append(tool.name)
        self.declared_tools.append(names)
        return super().bind_tools(tools, **options)


async def test_dynamic_tools_after_skills_disclosure_are_in_final_budget_snapshot(tmp_path):
    _, context, _, original, _, _ = assemble([])
    history = JsonlSessionStore(
        tmp_path / "session", SessionHeader(context.session_id, str(tmp_path), "t"), TestTiming()
    )
    context.journal = history
    context.history_store = history
    context.projector = ContextProjector()

    class NewTool:
        spec = replace(original.spec, name="dynamic", source="mcp:test")

    class RefreshingMCP:
        framework_tool = None

        async def refresh(self, registry):
            registry.replace_source("mcp:", (NewTool(),))

    context.mcp = RefreshingMCP()
    model = ToolCapturingModel(responses=[AIMessage(content="done")])
    runtime = AgentRuntime(model, skills=[])
    try:
        result = await runtime.run((Message("user", "user", "inspect"),), context)
        assert result.outcome == "completed"
        assert "dynamic" in model.declared_tools[-1]
        final = context.projector.project(await history.snapshot()).checkpoint["model_tools"]
        assert {spec["name"] for spec in final} == set(model.declared_tools[-1])
        assert "dynamic" in {spec.name for spec in context.model_tools}
    finally:
        await history.aclose()
