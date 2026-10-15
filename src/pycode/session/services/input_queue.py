# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/agent/src/agent.ts
# Adaptation: explicit per-run owner and stable IDs.
"""Steering and follow-up queues consumed only by the settled runtime boundary."""

from collections import deque

from pycode.kernel import Message, clone_value
from pycode.session.domain.inputs import InputKind


class PendingInputs:
    def __init__(self) -> None:
        self._steer: deque[Message] = deque()
        self._follow_up: deque[Message] = deque()
        self._accepted: dict[str, tuple[Message, InputKind]] = {}

    def submit(self, message: Message, kind: InputKind) -> bool:
        if message.role != "user" or not message.id:
            raise ValueError("Queued input must be a user message with a stable ID")
        if kind not in {"steer", "follow_up"}:
            raise ValueError("Unknown input queue kind")
        previous = self._accepted.get(message.id)
        if previous is not None:
            if previous != (message, kind):
                raise ValueError("Input ID already exists with different content")
            return False
        copied = clone_value(message)
        self._accepted[message.id] = (copied, kind)
        queue = self._steer if kind == "steer" else self._follow_up
        queue.append(copied)
        return True

    def has(self, kind: InputKind) -> bool:
        queue = self._steer if kind == "steer" else self._follow_up
        return bool(queue)

    def take(self, kind: InputKind, *, one_at_a_time: bool = False) -> tuple[Message, ...]:
        queue = self._steer if kind == "steer" else self._follow_up
        if not queue:
            return ()
        if one_at_a_time:
            return (queue.popleft(),)
        messages = tuple(queue)
        queue.clear()
        return messages
