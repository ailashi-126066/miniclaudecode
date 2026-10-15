"""Critical failures and provider-only declarations stay outside recoverable selection errors."""

from dataclasses import replace
from types import SimpleNamespace
from typing import Any

import pytest
from langchain.agents.middleware import ModelRequest
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.runnables import RunnableLambda
from langchain_core.tools import StructuredTool

from pycode.app.composition.runtime import build_agent_runtime
from pycode.kernel.domain.errors import StorageError
from pycode.kernel.domain.messages import Message
from pycode.kernel.services.events import EventStream
from pycode.models.adapters.tool_selection import ToolSelector
from tests.agent.adapters.test_agent import assemble, tool_response
from tests.models.adapters.test_tool_selection import selection_settings
from tests.support.model import ScriptedChatModel


class ChoiceModel(ScriptedChatModel):
    choices: list[str]

    def with_structured_output(self, schema, **options):
        async def select(messages):
            return {"tools": self.choices}

        return RunnableLambda(select)


class Owner:
    def __init__(self, choices):
        self.model = ChoiceModel(responses=[], choices=choices)
        self.closed = False

    async def aclose(self):
        self.closed = True


def request_for_selection():
    async def execute():
        return "unused"

    tools = []
    for name in ("one", "two", "three"):
        tools.append(
            StructuredTool(
                name=name,
                description=name,
                args_schema={"type": "object", "properties": {}},
                coroutine=execute,
            )
        )
    context = SimpleNamespace(
        run_id="r",
        plans=None,
        selected_tool_names=None,
        history_store=None,
        events=EventStream("s", "r", ()),
    )
    request = ModelRequest(
        model=ScriptedChatModel(responses=[]),
        messages=[HumanMessage(content="select")],
        tools=tools,
        state={"turn": 1},
        runtime=SimpleNamespace(context=context),
    )
    return request, context


def owned_factory(owner):
    async def create(*args):
        return owner

    return create


async def test_provider_dictionary_is_preserved_without_becoming_a_selector_candidate():
    request, context = request_for_selection()
    request = request.override(tools=[*request.tools, {"type": "web_search"}])
    owner = Owner(["two"])
    selector = ToolSelector(selection_settings(), owned_factory(owner), required_tools=())
    selected = await selector.select(request)
    assert [tool.name for tool in selected.tools if not isinstance(tool, dict)] == ["two"]
    assert selected.tools[-1] == {"type": "web_search"}
    assert owner.closed
    retried = await selector.select(request)
    assert retried.tools == selected.tools


async def test_programming_error_is_not_silently_recovered_as_all_tools():
    request, _ = request_for_selection()

    async def fail(*args):
        raise ValueError("programming defect")

    selector = ToolSelector(selection_settings(), fail, required_tools=())
    with pytest.raises(ValueError, match="programming defect"):
        await selector.select(request)
    assert selector.snapshots == {}


async def test_failed_audit_persistence_propagates_after_owned_model_is_closed():
    request, context = request_for_selection()

    class History:
        async def append_entry(self, entry):
            raise StorageError("disk_failed", "test")

    context.history_store = History()
    owner = Owner(["two"])
    selector = ToolSelector(selection_settings(), owned_factory(owner), required_tools=())
    with pytest.raises(StorageError) as error:
        await selector.select(request)
    assert error.value.code == "disk_failed" and owner.closed


async def test_selected_tool_changed_after_model_declaration_is_not_executed():
    _, context, _, tool, journal, _ = assemble([])
    owner = Owner(["echo"])
    context.tool_selector = ToolSelector(
        selection_settings(), owned_factory(owner), required_tools=()
    )

    class ChangingModel(ScriptedChatModel):
        context: Any
        domain_tool: Any

        def next_response(self, messages):
            response = super().next_response(messages)
            if response.tool_calls:
                self.domain_tool.spec = replace(
                    self.domain_tool.spec, implementation_hash="changed"
                )
                self.context.registry.replace_source("builtin", (self.domain_tool,))
            return response

    model = ChangingModel(
        responses=[tool_response(), AIMessage(content="done")], context=context, domain_tool=tool
    )
    runtime = build_agent_runtime(model)
    result = await runtime.run((Message("u", "user", "inspect"),), context)
    assert result.outcome == "completed"
    assert tool.executed == []
    results = [
        message.tool_result for message in journal.messages if message.tool_result is not None
    ]
    assert len(results) == 1 and results[0].error_code == "tool_declaration_changed"
    assert results[0].details["executed"] is False


async def test_required_tool_names_are_explicit_composition_input():
    request, _ = request_for_selection()
    owner = Owner(["two"])
    required = ["one"]
    selector = ToolSelector(selection_settings(), owned_factory(owner), required_tools=required)
    required.clear()
    selected = await selector.select(request)
    assert {tool.name for tool in selected.tools} == {"one", "two"}
    assert selector.required_tools == frozenset({"one"})
    assert owner.closed


async def test_module_required_names_are_refreshed_for_each_new_model_turn():
    request, _ = request_for_selection()
    owner = Owner(["two"])
    current = ["one"]
    selector = ToolSelector(
        selection_settings(), owned_factory(owner), required_tools=lambda: current
    )
    first = await selector.select(request)
    assert {tool.name for tool in first.tools} == {"one", "two"}
    current[:] = ["three"]
    request.state["turn"] = 2
    second = await selector.select(request)
    assert {tool.name for tool in second.tools} == {"three", "two"}
