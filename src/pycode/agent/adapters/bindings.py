"""Explicit framework dependencies assembled outside the agent feature."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import BaseMessage

from pycode.kernel import Message, ModelSelection


@dataclass(frozen=True)
class RuntimeBindings:
    to_framework: Callable[[Message, ModelSelection], BaseMessage]
    from_assistant: Callable[..., Message]
    make_framework_tool: Callable[..., object]
    select_model_tools: Callable[[object], Awaitable[object]]
    calibrate_model_request: Callable[[object], Awaitable[object]]
    model_request_step: Callable[..., object]
    skills_middleware_factory: Callable[..., AgentMiddleware]
    settlement_middleware_factory: Callable[[object], AgentMiddleware]
    run_hook: Callable[..., Awaitable[None]]
