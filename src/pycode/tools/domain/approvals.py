"""Validate external answers before they can become durable graph resume values."""

from pycode.kernel import StorageError
from pycode.tools.domain.questions import validate_question_answer


def validate_operation_answer(question: dict, answer: dict) -> None:
    if not isinstance(answer, dict) or answer.get("operation_id") != question["operation_id"]:
        raise StorageError("invalid_resume", "Answer must identify the pending operation")
    if question["kind"] == "permission":
        if answer.get("decision") not in ("approve", "deny"):
            raise StorageError("invalid_approval", "Approval decision must be approve or deny")
        return
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
