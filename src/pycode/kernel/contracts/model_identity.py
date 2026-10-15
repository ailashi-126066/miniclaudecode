"""Framework-independent model identity recorded in messages and history."""

from dataclasses import dataclass, field
from typing import Any, Literal

ProviderKind = Literal["deepseek", "openai", "anthropic"]


@dataclass(frozen=True)
class ModelCapabilities:
    context_window: int | None = None
    max_output_tokens: int | None = None
    supports_tools: bool = False
    supports_thinking: bool = False
    supports_images: bool = False


@dataclass(frozen=True)
class ModelSelection:
    provider: str
    model_id: str
    implementation: ProviderKind
    thinking: str = "off"
    capabilities: ModelCapabilities = field(default_factory=ModelCapabilities)
    thinking_parameters: dict[str, Any] = field(default_factory=dict)
    reasoning_effort: str | None = None
    api_mode: Literal["chat_completions", "responses"] | None = None
