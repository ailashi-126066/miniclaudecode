"""Framework-owned tool execution and pycode's narrow repeated-failure guard."""

import pytest
from langchain_core.messages import AIMessage

from pycode.kernel.contracts.messages import Message, ToolResult
from pycode.kernel.errors import ToolError
from tests.agent.adapters.test_agent import assemble


def response(identifier, value="same", name="echo"):
    return AIMessage(
        id="assistant-" + identifier,
        content="",
        tool_calls=[{"id": identifier, "name": name, "args": {"value": value}}],
    )


async def test_fourth_and_later_identical_failure_is_paired_but_never_executed():
    responses = [response(str(index)) for index in range(6)]
    responses.append(AIMessage(id="final", content="changed approach"))
    runtime, context, _, tool, journal, _ = assemble(responses)

    class AlwaysFails:
        spec = tool.spec
        executed = 0

        async def execute(self, tool_context, arguments):
            self.executed += 1
            raise ToolError("stable_failure", "bad input")

    failing = AlwaysFails()
    context.registry.replace_source("builtin", (failing,))
    result = await runtime.run((Message("u1", "user", "work"),), context)
    assert result.outcome == "completed"
    assert failing.executed == 3
    results = [message.tool_result for message in journal.messages if message.tool_result]
    assert [result.error_code for result in results] == ["stable_failure"] * 3 + [
        "repeated_tool_failure"
    ] * 3
    assert all(result.details["executed"] is False for result in results[3:])
    assert [result.call_id for result in results] == [str(index) for index in range(6)]
    assert runtime.graph.config["recursion_limit"] == 9999
    assert not any("SummarizationMiddleware" in name for name in runtime.graph.get_graph().nodes)


async def test_parallel_identical_calls_stop_at_three_actual_failures():
    replies = [
        response("prior"),
        AIMessage(
            id="batch",
            content="",
            tool_calls=[
                {"id": str(index), "name": "echo", "args": {"value": "same"}} for index in range(5)
            ],
        ),
        AIMessage(id="done", content="choose another approach"),
    ]
    runtime, context, _, tool, journal, _ = assemble(replies)

    class Failure:
        spec = tool.spec
        count = 0

        async def execute(self, current, arguments):
            self.count += 1
            raise ToolError("stable_failure", "bad input")

    failing = Failure()
    context.registry.replace_source("builtin", (failing,))
    await runtime.run((Message("u", "user", "work"),), context)
    assert failing.count == 3
    results = [message.tool_result for message in journal.messages if message.tool_result]
    assert [result.error_code for result in results] == ["stable_failure"] * 3 + [
        "repeated_tool_failure"
    ] * 3


@pytest.mark.parametrize("change", ["arguments", "error", "success"])
async def test_real_changes_reset_the_narrow_failure_counter(change):
    responses = [response(str(index)) for index in range(4)]
    if change == "arguments":
        responses[-1] = response("3", "new input")
    responses.append(AIMessage(id="final", content="done"))
    runtime, context, _, tool, journal, _ = assemble(responses)

    class SometimesFails:
        spec = tool.spec
        executed = 0

        async def execute(self, tool_context, arguments):
            self.executed += 1
            if self.executed == 3 and change == "success":
                return ToolResult("", "", "success")
            code = (
                "changed_failure" if self.executed == 3 and change == "error" else "stable_failure"
            )
            raise ToolError(code, "failed")

    failing = SometimesFails()
    context.registry.replace_source("builtin", (failing,))
    await runtime.run((Message("u1", "user", "work"),), context)
    assert failing.executed == 4
    assert not any(
        message.tool_result.error_code == "repeated_tool_failure"
        for message in journal.messages
        if message.tool_result
    )


@pytest.mark.parametrize("case", ["unknown", "malformed"])
async def test_unknown_and_malformed_calls_have_one_error_pair_and_no_effect(case):
    reply = response("c1", name="unknown" if case == "unknown" else "echo")
    if case == "malformed":
        reply = AIMessage(
            id="bad",
            content="",
            invalid_tool_calls=[
                {"id": "c1", "name": "echo", "args": "{bad", "error": "Malformed JSON"}
            ],
        )
    runtime, context, _, tool, journal, _ = assemble([reply, AIMessage(id="done", content="fixed")])
    result = await runtime.run((Message("u1", "user", "work"),), context)
    assert result.outcome == "completed" and result.final_text == "fixed"
    assert tool.executed == []
    results = [message.tool_result for message in journal.messages if message.tool_result]
    assert len(results) == 1 and results[0].call_id == "c1"
    assert results[0].error_code == ("unknown_tool" if case == "unknown" else "invalid_arguments")


async def test_native_tool_retry_retries_only_declared_temporary_read_errors():
    runtime, context, _, tool, journal, _ = assemble(
        [response("c1"), AIMessage(id="done", content="fixed")]
    )

    class TemporaryRead:
        spec = tool.spec
        attempts = 0

        async def execute(self, tool_context, arguments):
            self.attempts += 1
            if self.attempts == 1:
                raise ToolError("temporary_read_failure", "temporary", retryable=True)
            return ToolResult("", "", "read completed")

    reader = TemporaryRead()
    context.registry.replace_source("builtin", (reader,))
    result = await runtime.run((Message("u1", "user", "read"),), context)
    assert result.outcome == "completed" and reader.attempts == 2
    results = [message.tool_result for message in journal.messages if message.tool_result]
    assert len(results) == 1 and results[0].text == "read completed"
