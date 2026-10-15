"""The replaceable boundary for an external user answer."""

from typing import Protocol


class QuestionPrompt(Protocol):
    async def ask(self, payload: dict) -> dict: ...
