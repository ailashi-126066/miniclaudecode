"""MCP elicitation validation and durable question receipt ownership."""

from pycode.kernel import EntryDraft, HistoryJournal, StorageError, ToolError
from pycode.mcp.ports.schema import SchemaChecker


def validate_elicitation_answer(question: dict, answer: dict) -> None:
    if not isinstance(answer, dict) or not isinstance(answer.get("responses"), dict):
        raise StorageError("invalid_resume", "MCP elicitation requires keyed responses")
    responses = answer["responses"]
    keys = set()
    for request in question["requests"]:
        keys.add(request["key"])
        response = responses.get(request["key"])
        if not isinstance(response, dict) or response.get("action") not in {
            "accept",
            "decline",
            "cancel",
        }:
            raise StorageError("invalid_resume", "Each MCP question requires an explicit action")
        if (
            response["action"] == "accept"
            and request["mode"] == "form"
            and not isinstance(response.get("content"), dict)
        ):
            raise StorageError("invalid_resume", "Accepted MCP forms require object content")
    if set(responses) != keys:
        raise StorageError("invalid_resume", "MCP responses must match the current questions")


class MCPQuestionExtension:
    def validate(self, question: dict, answer: dict, validator: SchemaChecker) -> bool:
        if question.get("type") != "mcp_elicitation":
            return False
        validate_elicitation_answer(question, answer)
        for request in question["requests"]:
            response = answer["responses"][request["key"]]
            if request["mode"] != "form" or response["action"] != "accept":
                continue
            try:
                validator.check_schema(request["requested_schema"])
                validator.validate(request["requested_schema"], response["content"])
            except ToolError as error:
                raise StorageError(
                    "invalid_resume", "MCP answer does not satisfy the requested schema"
                ) from error
        return True

    async def prepare(self, question: dict, history: HistoryJournal | None, run_id: str) -> dict:
        if question.get("type") != "mcp_elicitation":
            return question
        question = {**question, "kind": "mcp_elicitation"}
        if history is not None:
            snapshot = await history.snapshot()
            if not any(
                entry.type == "mcp_elicitation"
                and entry.data.get("interrupt_id") == question["interrupt_id"]
                for entry in snapshot.entries
            ):
                await history.append_entry(
                    EntryDraft("mcp_elicitation", data=question, run_id=run_id)
                )
        return question
