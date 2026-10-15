"""Validate external answers before they can become durable graph resume values."""

from pycode.kernel import StorageError
from pycode.tools.domain.questions import validate_question_answer


def validate_operation_answer(question: dict, answer: dict) -> None:
    if question.get("type") == "mcp_elicitation":
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
                raise StorageError(
                    "invalid_resume", "Each MCP question requires an explicit action"
                )
            if (
                response["action"] == "accept"
                and request["mode"] == "form"
                and not isinstance(response.get("content"), dict)
            ):
                raise StorageError("invalid_resume", "Accepted MCP forms require object content")
        if set(responses) != keys:
            raise StorageError("invalid_resume", "MCP responses must match the current questions")
        return
    if not isinstance(answer, dict) or answer.get("operation_id") != question["operation_id"]:
        raise StorageError("invalid_resume", "Answer must identify the pending operation")
    if question["kind"] == "permission":
        if answer.get("decision") not in ("approve", "deny"):
            raise StorageError("invalid_approval", "Approval decision must be approve or deny")
        return
    if question["kind"] == "tool_reconcile":
        action = answer.get("action")
        if action in ("retry", "abandon"):
            return
        if action == "result" and isinstance(answer.get("text"), str):
            if isinstance(answer.get("is_error", False), bool):
                return
        raise StorageError("invalid_reconciliation", "Review requires retry, abandon or a result")
    if question["kind"] == "ask_user":
        validate_question_answer(question, answer)
        return
    if question["kind"] == "plan":
        if answer.get("plan_id") != question["plan_id"] or answer.get("decision") not in {
            "auto",
            "review",
            "feedback",
        }:
            raise StorageError(
                "invalid_plan_answer", "Answer must identify the Plan and an approval choice"
            )
        if answer["decision"] == "feedback" and (
            not isinstance(answer.get("feedback"), str) or not answer["feedback"].strip()
        ):
            raise StorageError("invalid_plan_answer", "Plan feedback must be text")
        return
    raise StorageError("invalid_resume", "This interruption does not accept operation answers")
