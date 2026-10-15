"""Plan approval waits exist only in the current in-memory LangGraph run."""

from langgraph.func import task
from langgraph.types import interrupt

from pycode.kernel import StorageError


@task(name="pycode_plan_approval")
async def plan_answer(payload):
    return interrupt(payload)


class InterruptPlanPrompt:
    async def ask(self, payload):
        answer = await plan_answer(payload)
        if (
            not isinstance(answer, dict)
            or answer.get("operation_id") != payload["operation_id"]
            or answer.get("plan_id") != payload["plan_id"]
        ):
            raise StorageError("invalid_plan_answer", "Plan answer must identify the active review")
        if answer.get("decision") not in {"auto", "review", "feedback"}:
            raise StorageError("invalid_plan_answer", "Choose auto, review or feedback")
        return answer
