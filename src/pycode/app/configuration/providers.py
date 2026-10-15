"""Read and validate external Provider JSON at the application boundary."""

import json
import re
from dataclasses import fields
from pathlib import Path
from urllib.parse import urlsplit

from pydantic import TypeAdapter, ValidationError

from pycode.kernel import DomainError
from pycode.models import (
    CapabilityOverride,
    ModelReference,
    ProviderConfig,
    ToolSelectorSettings,
    is_loopback_host,
)


def load_providers(path: Path) -> tuple[ProviderConfig, ...]:
    try:
        raw = path.read_bytes()
        objects = json.loads(raw)
        validate_provider_fields(objects)
        providers = TypeAdapter(list[ProviderConfig]).validate_json(raw, strict=True)
    except (OSError, ValidationError, ValueError) as error:
        raise DomainError(
            "invalid_provider_config", "Cannot read or validate Provider configuration"
        ) from error
    names = set()
    for config in providers:
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", config.name) or config.name in names:
            raise DomainError("invalid_provider_config", "Provider names must be stable and unique")
        names.add(config.name)
        validate_provider(config)
    validate_provider_references(providers, names)
    return tuple(providers)


def validate_provider_fields(objects):
    allowed_fields = {field.name for field in fields(ProviderConfig)}
    if not isinstance(objects, list):
        raise ValueError("Providers must be a list")
    for item in objects:
        if not isinstance(item, dict) or not set(item) <= allowed_fields:
            raise ValueError("Unexpected Provider field")
        selector = item.get("tool_selector", {})
        selector_fields = {field.name for field in fields(ToolSelectorSettings)}
        if not isinstance(selector, dict) or not set(selector) <= selector_fields:
            raise ValueError("Unexpected tool selector field")
        fallbacks = item.get("fallback_models", [])
        if not isinstance(fallbacks, list) or len(fallbacks) > 4:
            raise ValueError("Use at most four fallback model references")
        for fallback in fallbacks:
            if not isinstance(fallback, dict) or set(fallback) != {"provider", "model_id"}:
                raise ValueError("Invalid fallback model reference")
        reference = selector.get("model")
        reference_fields = {field.name for field in fields(ModelReference)}
        if reference is not None and (
            not isinstance(reference, dict) or set(reference) != reference_fields
        ):
            raise ValueError("Invalid tool selector model reference")
        overrides = item.get("model_overrides", {})
        override_fields = {field.name for field in fields(CapabilityOverride)}
        if not isinstance(overrides, dict):
            raise ValueError("Model overrides must be an object")
        for override in overrides.values():
            if not isinstance(override, dict) or not set(override) <= override_fields:
                raise ValueError("Unexpected capability override field")


def validate_provider_url(base_url: str):
    try:
        url = urlsplit(base_url)
        valid_port = url.port is None or 0 < url.port <= 65535
    except ValueError as error:
        raise DomainError("invalid_provider_config", "Provider URL is malformed") from error
    if (
        not valid_port
        or url.scheme not in {"http", "https"}
        or not url.hostname
        or url.username
        or url.password
    ):
        raise DomainError(
            "invalid_provider_config", "Provider base URL must be HTTP(S) without credentials"
        )
    if url.scheme != "https" and not is_loopback_host(url.hostname):
        # API keys travel in request headers; plain HTTP is only acceptable on this machine.
        raise DomainError(
            "invalid_provider_config", "Provider base URL must use HTTPS unless loopback"
        )
    if url.query or url.fragment:
        raise DomainError(
            "invalid_provider_config", "Provider base URL cannot contain query or fragment"
        )


def validate_provider(config: ProviderConfig):
    validate_provider_url(config.base_url)
    if not re.fullmatch(r"[A-Z_][A-Z0-9_]*", config.api_key_env):
        raise DomainError(
            "invalid_provider_config", "API keys must be environment variable references"
        )
    if (
        not config.model_id
        or config.context_window <= config.max_output_tokens
        or config.max_output_tokens <= 0
    ):
        raise DomainError(
            "invalid_provider_config", "Model ID and input/output capacities must be valid"
        )
    if config.timeout_seconds <= 0:
        raise DomainError("invalid_provider_config", "Provider timeout must be positive")
    if config.implementation == "openai" and config.openai_api is None:
        raise DomainError("invalid_provider_config", "OpenAI API mode must be selected explicitly")
    if config.implementation != "openai" and config.openai_api is not None:
        raise DomainError(
            "invalid_provider_config", "OpenAI API mode does not apply to this Provider"
        )
    validate_thinking(config)
    if config.reasoning_effort is not None:
        if not config.supports_thinking or config.reasoning_effort not in {
            "minimal",
            "low",
            "medium",
            "high",
            "xhigh",
            "max",
        }:
            raise DomainError(
                "invalid_provider_config",
                "Reasoning effort requires declared thinking capability",
            )
        if config.thinking.get("type") == "disabled":
            raise DomainError(
                "invalid_provider_config", "Reasoning effort conflicts with disabled thinking"
            )
    validate_provider_overrides(config)


def validate_provider_overrides(config: ProviderConfig):
    for model_id, capabilities in config.model_overrides.items():
        if not model_id:
            raise DomainError("invalid_provider_config", "Model override ID must not be empty")
        if capabilities.context_window is not None and capabilities.context_window <= 0:
            raise DomainError("invalid_provider_config", "Model context override must be positive")
        if capabilities.max_output_tokens is not None and capabilities.max_output_tokens <= 0:
            raise DomainError("invalid_provider_config", "Model output override must be positive")
        if (
            capabilities.context_window is not None
            and capabilities.max_output_tokens is not None
            and capabilities.context_window <= capabilities.max_output_tokens
        ):
            raise DomainError("invalid_provider_config", "Model override leaves no input capacity")


def validate_provider_references(providers, names):
    for provider in providers:
        references = [
            (reference.provider, reference.model_id) for reference in provider.fallback_models
        ]
        if (
            len(references) != len(set(references))
            or (provider.name, provider.model_id) in references
        ):
            raise DomainError(
                "invalid_provider_config",
                "Fallback references must be unique and exclude the primary model",
            )
        if any(reference.provider not in names for reference in provider.fallback_models):
            raise DomainError("invalid_provider_config", "Fallback Provider is not configured")
        reference = provider.tool_selector.model
        if reference is not None and reference.provider not in names:
            raise DomainError("invalid_provider_config", "Tool selector Provider is not configured")


def validate_thinking(config: ProviderConfig) -> None:
    if not config.thinking:
        return
    if not config.supports_thinking:
        raise DomainError(
            "invalid_provider_config", "Thinking must be declared as a model capability"
        )
    options = config.thinking
    if config.implementation == "deepseek":
        mode = options.get("type")
        valid = (
            isinstance(mode, str) and set(options) == {"type"} and mode in {"enabled", "disabled"}
        )
    elif config.implementation == "openai":
        effort = options.get("effort")
        valid = isinstance(effort, str) and effort in {
            "none",
            "minimal",
            "low",
            "medium",
            "high",
            "xhigh",
        }
        valid = valid and set(options) <= {"effort", "summary"}
        if "summary" in options:
            valid = (
                valid
                and isinstance(options["summary"], str)
                and options["summary"] in {"auto", "concise", "detailed"}
            )
        if config.openai_api == "chat_completions":
            valid = valid and set(options) == {"effort"}
    else:
        budget = options.get("budget_tokens")
        valid = options.get("type") == "adaptive" and set(options) == {"type"}
        if options.get("type") == "enabled":
            valid = (
                set(options) == {"type", "budget_tokens"}
                and isinstance(budget, int)
                and not isinstance(budget, bool)
                and 1024 <= budget < config.max_output_tokens
            )
    if not valid:
        raise DomainError("invalid_provider_config", "Invalid Provider thinking options")
