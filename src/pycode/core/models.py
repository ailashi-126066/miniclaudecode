"""Stable selected model identity without provider client types or secrets."""

from dataclasses import dataclass, field
from typing import Any, Literal

from pycode.core.values import clone_value

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


@dataclass(frozen=True)
class CapabilityOverride:
    context_window: int | None = None
    max_output_tokens: int | None = None
    supports_tools: bool | None = None
    supports_thinking: bool | None = None
    supports_images: bool | None = None


@dataclass(frozen=True)
class ModelReference:
    provider: str
    model_id: str

    def __post_init__(self):
        if not self.provider or not self.model_id:
            raise ValueError("Model references require provider and model ID")


@dataclass(frozen=True)
class ToolSelectorSettings:
    enabled: bool = True
    model: ModelReference | None = None
    max_tools: int = 8
    candidate_threshold: int = 24
    schema_token_threshold: int = 12000
    input_limit: int = 16000
    output_limit: int = 1024
    always_include: tuple[str, ...] = ()
    on_failure: Literal["all", "error"] = "all"

    def __post_init__(self):
        if not isinstance(self.enabled, bool):
            raise ValueError("Tool selection enabled must be boolean")
        for value in (
            self.max_tools,
            self.candidate_threshold,
            self.schema_token_threshold,
            self.input_limit,
            self.output_limit,
        ):
            if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                raise ValueError("Tool selection limits must be positive integers")
        if self.on_failure not in {"all", "error"}:
            raise ValueError("Tool selection failure policy must be all or error")
        if any(not isinstance(name, str) or not name for name in self.always_include):
            raise ValueError("Always included tools require names")
        if len(set(self.always_include)) != len(self.always_include):
            raise ValueError("Always included tools must be unique")


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
    tool_selector: ToolSelectorSettings = field(default_factory=ToolSelectorSettings)
    fallback_models: tuple[ModelReference, ...] = ()


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
