"""A single metered model request proposes JSON memory candidates; it runs no tools."""

import json
from dataclasses import dataclass

from langchain.agents.middleware import ModelResponse
from langchain_core.messages import HumanMessage, SystemMessage

from pycode.core.errors import ToolError
from pycode.core.session_tree import SessionTree
from pycode.core.token_estimate import estimate_text
from pycode.core.values import json_dumps


@dataclass
class MemoryRequest:
    messages: list


MEMORY_SYSTEM = (
    "Extract only durable user preferences, correction feedback, and project knowledge. "
    "Return a JSON array, with at most 16 objects. Each object has action (add/update/remove), "
    "key, category (user_preference/correction_feedback/project_knowledge), scope (user/project), "
    "content, durable, evidence (entry_id and exact quote), and optional target_id. "
    "Do not invent evidence. Exclude secrets and task-local requirements. "
    "Assistant claims alone are not evidence. Return [] when there is no durable information."
)


class MemoryWorkflow:
    def __init__(self, model, requests, *, input_limit=16000):
        self.model = model
        self.requests = requests
        self.input_limit = input_limit

    async def extract(self, snapshot, run_id, *, full=False):
        messages = []
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        previous_leaf = None
        for entry in reversed(path):
            if entry.type == "memory_extract" and entry.data.get("status") == "completed":
                previous_leaf = entry.data.get("source_leaf_id")
                break
        start = 0
        for index, entry in enumerate(path):
            if not full and entry.id == previous_leaf:
                start = index + 1
                break
        for entry in path[start:]:
            if entry.message is None:
                continue
            message = entry.message
            text = message.text
            if message.tool_result is not None:
                text = message.tool_result.text
            messages.append({"entry_id": entry.id, "role": message.role, "text": text[:4000]})
        if not messages:
            return ()
        selected = []
        used = estimate_text(MEMORY_SYSTEM) + 64
        for message in reversed(messages):
            cost = estimate_text(json_dumps(message, ensure_ascii=False)) + 8
            if used + cost > self.input_limit:
                break
            selected.append(message)
            used += cost
        selected.reverse()
        if not selected:
            raise ToolError("memory_source_overflow", "Memory evidence exceeds the input budget")
        request = MemoryRequest(
            [
                SystemMessage(content=MEMORY_SYSTEM),
                HumanMessage(content=json_dumps(selected, ensure_ascii=False)),
            ]
        )

        async def invoke(current):
            return ModelResponse(result=[await self.model.ainvoke(current.messages, stream=True)])

        response = await self.requests.invoke(request, invoke, logical_id="memory_extract")
        assistant = response.result[-1]
        finish = assistant.response_metadata.get(
            "finish_reason", assistant.response_metadata.get("stop_reason")
        )
        if finish in {"length", "max_tokens", "content_filter"}:
            raise ToolError(
                "memory_extract_incomplete", "Memory extraction response was incomplete"
            )
        if assistant.tool_calls or assistant.invalid_tool_calls:
            raise ToolError("memory_extractor_tool_call", "Memory extraction cannot call tools")
        text = assistant.text.strip()
        if text.startswith("```json") and text.endswith("```"):
            text = text[7:-3].strip()
        try:
            values = json.loads(text)
        except ValueError as error:
            raise ToolError(
                "invalid_memory_extract", "Memory extractor did not return JSON"
            ) from error
        if not isinstance(values, list) or len(values) > 16:
            raise ToolError(
                "invalid_memory_extract", "Memory extraction requires a bounded candidate list"
            )
        for item in values:
            if not isinstance(item, dict):
                raise ToolError("invalid_memory_extract", "Memory candidates must be objects")
        return tuple(values)
