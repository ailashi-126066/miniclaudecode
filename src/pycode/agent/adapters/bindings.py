"""Explicit framework dependencies assembled outside the agent feature."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Protocol

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import BaseMessage

from pycode.agent.services.context import RunContext
from pycode.kernel import HistoryJournal, Message, ModelSelection
from pycode.tools import ArgumentValidator


class FrameworkExtension(Protocol):
    prepares_model_boundary: bool

    def middleware(
        self,
        context: RunContext,
        backend: object,
        model_boundary: Callable,
        tool_converter: Callable,
    ) -> AgentMiddleware[Any, Any, Any]: ...


class QuestionExtension(Protocol):
    def validate(self, question: dict, answer: dict, validator: ArgumentValidator) -> bool: ...
    async def prepare(
        self, question: dict, history: HistoryJournal | None, run_id: str
    ) -> dict: ...


@dataclass(frozen=True)
class RuntimeBindings:
    to_framework: Callable[[Message, ModelSelection], BaseMessage]
    from_assistant: Callable[..., Message]
    make_framework_tool: Callable[..., object]
    select_model_tools: Callable[[object], Awaitable[object]]
    calibrate_model_request: Callable[[object], Awaitable[object]]
    model_request_step: Callable[..., object]
    # SDK middleware states differ (SkillsState versus ToolSettlementState).
    # Erase only these SDK generic parameters; native runtime capabilities stay typed.
    settlement_middleware_factory: Callable[[RunContext], AgentMiddleware[Any, Any, Any]]
    run_hook: Callable[..., Awaitable[None]]

    # Ordered optional capabilities are constructed by feature-owned modules.
    framework_extensions: tuple[FrameworkExtension, ...] = ()
    model_filters: tuple[Callable[[object], Awaitable[object]], ...] = ()
    before_model: tuple[Callable[[], Awaitable[None]], ...] = ()
    question_extensions: tuple[QuestionExtension, ...] = ()
