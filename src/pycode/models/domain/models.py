"""Stable selected model identity without provider client types or secrets."""

from dataclasses import dataclass, field
from typing import Any, Literal

from pycode.kernel import ModelCapabilities, ModelSelection, ProviderKind, clone_value


@dataclass(frozen=True)
class CapabilityOverride:
    context_window: int | None = None
    max_output_tokens: int | None = None
    supports_tools: bool | None = None
    supports_thinking: bool | None = None
    supports_images: bool | None = None


@dataclass(frozen=True)
class ProviderConfig:
    name: str
    implementation: ProviderKind
    model_id: str
    base_url: str
    api_key_env: str
    context_window: int
    max_output_tokens: int
    openai_api: Literal["chat_completions", "responses"] | None = None
    thinking: dict[str, Any] = field(default_factory=dict)
    supports_tools: bool = False
    supports_thinking: bool = False
    supports_images: bool = False
    timeout_seconds: float = 60
    model_overrides: dict[str, CapabilityOverride] = field(default_factory=dict)
    reasoning_effort: str | None = None


@dataclass(frozen=True)
class DiscoveredModel:
    model_id: str
    capabilities: ModelCapabilities = field(default_factory=ModelCapabilities)


@dataclass(frozen=True)
class ModelDescriptor:
    provider: str
    model_id: str
    implementation: ProviderKind
    capabilities: ModelCapabilities
    availability: Literal["available", "unknown", "unavailable"]
    source: Literal["configured", "server", "cache", "override"]


@dataclass(frozen=True)
class CatalogSnapshot:
    config_hash: str
    refreshed_at: float
    models: tuple[ModelDescriptor, ...] = ()
    errors: dict[str, str] = field(default_factory=dict)


def model_selection(config: ProviderConfig) -> ModelSelection:
    thinking = (
        config.reasoning_effort
        or config.thinking.get("type")
        or config.thinking.get("effort")
        or "off"
    )
    if thinking == "disabled":
        thinking = "off"
    capabilities = ModelCapabilities(
        config.context_window,
        config.max_output_tokens,
        config.supports_tools,
        config.supports_thinking,
        config.supports_images,
    )
    return ModelSelection(
        config.name,
        config.model_id,
        config.implementation,
        str(thinking),
        capabilities,
        clone_value(config.thinking),
        config.reasoning_effort,
        config.openai_api,
    )
