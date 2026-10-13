# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/compaction/compaction.ts
# Adaptation: one metered BaseChatModel request, without an extra Agent loop.
"""An independent framework-owned summary request cannot execute tools."""

from dataclasses import dataclass

from langchain.agents.middleware import ModelResponse
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from pycode.core.errors import ModelError
from pycode.core.summaries import SummaryCompletion, SummarySource
from pycode.core.tool_text import model_tool_text
from pycode.core.values import json_dumps
from pycode.workflows.messages import from_assistant, stop_reason
from pycode.workflows.model_requests import ModelRequestRunner

SUMMARY_SYSTEM = (
    "You create context checkpoints for another coding agent. Summarize the supplied historical "
    "conversation rather than continuing it. Do not call tools. "
    "Preserve the user's goal, constraints, "
    "decisions, completed work, failures, unfinished tasks, file changes, and stable output IDs. "
    "Do not claim an uncertain tool succeeded. "
    "Use headings Goal, Constraints, Progress, Decisions, "
    "Next Steps, and Critical Context. A turn-prefix section describes the task being split; "
    "preserve its requirements so the retained assistant/tool messages can continue that task."
)


def serialize_source(source: SummarySource) -> str:
    sections = ["<conversation>"]
    for index, message in enumerate(source.messages):
        if source.turn_prefix_start == index:
            sections.append("</conversation>\n<turn-prefix>")
        sections.append(f"[{message.role} id={message.id}]")
        if message.text:
            sections.append(message.text)
        for thinking in message.thinking:
            sections.append("[thinking]\n" + thinking.text)
        for call in message.tool_calls:
            sections.append(
                f"[call {call.id} {call.name}] "
                + json_dumps(call.arguments, ensure_ascii=False, sort_keys=True)
            )
        if message.tool_result is not None:
            result = message.tool_result
            sections.append(
                f"[result {result.call_id} output_id={result.output_id}]\n"
                + model_tool_text(result)
            )
        for attachment in message.attachments:
            sections.append(
                f"[attachment {attachment.media_type} reference={attachment.reference}]"
            )
    sections.append("</turn-prefix>" if source.turn_prefix_start is not None else "</conversation>")
    sections.append("Create a concise structured checkpoint. Preserve the incomplete task prefix.")
    return "\n".join(sections)


@dataclass
class SummaryRequest:
    messages: list


class SummaryWorkflow:
    def __init__(self, model: BaseChatModel):
        self._model = model

    async def run(self, source: SummarySource, requests: ModelRequestRunner) -> SummaryCompletion:
        request = SummaryRequest(
            [SystemMessage(content=SUMMARY_SYSTEM), HumanMessage(content=serialize_source(source))]
        )

        async def invoke(current):
            response = await self._model.ainvoke(current.messages, stream=True)
            return ModelResponse(result=[response])

        settled = await requests.invoke(request, invoke, logical_id="summary")
        response = settled.result[-1]
        if (
            not isinstance(response, AIMessage)
            or response.tool_calls
            or response.invalid_tool_calls
        ):
            raise ModelError("summary_tool_call", "Summary attempted a tool call; result rejected")
        if stop_reason(response) != "stop":
            raise ModelError("summary_incomplete", "Summary did not finish normally")
        message = from_assistant(response, source.selection, source.session_id, source.job_id)
        text = message.text.strip()
        if not text:
            raise ModelError("empty_summary", "Summary returned no text")
        if "<summary>" in text and "</summary>" in text:
            text = text.split("<summary>", 1)[1].split("</summary>", 1)[0].strip()
        if not text or ("<analysis>" in text and "<summary>" not in text):
            raise ModelError("empty_summary", "Summary contained only analysis")
        usage = tuple(
            record.usage for record in await requests.list_attempts() if record.usage is not None
        )
        return SummaryCompletion(text, usage)
