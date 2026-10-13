"""A real checkpoint-backed question wait, with no in-memory Future or timer."""

from langgraph.func import task
from langgraph.types import interrupt


@task(name="pycode_user_question")
async def user_answer(payload):
    return interrupt(payload)


class InterruptQuestionPrompt:
    async def ask(self, payload):
        return await user_answer(payload)
