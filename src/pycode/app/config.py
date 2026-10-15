"""Validate external configuration once at the application boundary."""

import json
import os
import re
from dataclasses import dataclass, field, fields
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
from pycode.sandbox import SandboxSettings, validate_passthrough
from pycode.tools import ToolGate


@dataclass(frozen=True)
class AppConfig:
    workspace: Path
    user_data: Path
    summary_keep_recent_tokens: int = 20000
    sandbox: SandboxSettings = field(default_factory=SandboxSettings)
    project_trusted: bool = False
    skill_index_budget: int = 2000
    memory_mode: str | None = None
    team_max_agents: int = 4
    read_only: bool = False
    allow_delegation: bool = True
    agent_id: str = "lead"
    request_database: Path | None = None
    isolated_worker: bool = False
    # Embedding hooks: a gate replacing the default tool authorization chain and
    # directories prepended to the Bash tool's PATH.
    tool_gate: ToolGate | None = None
    bash_path_prefix: tuple[str, ...] = ()
    credential_workspace: Path | None = None
    subprocess_passthrough: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if (
            isinstance(self.team_max_agents, bool)
            or not isinstance(self.team_max_agents, int)
            or not 1 <= self.team_max_agents <= 4
        ):
            raise ValueError("Team concurrency must be 1–4")
        validate_passthrough(self.subprocess_passthrough)
        if self.memory_mode not in {None, "auto", "off"}:
            raise ValueError("Memory mode must be auto or off")
        if (
            not isinstance(self.project_trusted, bool)
            or not isinstance(self.skill_index_budget, int)
            or isinstance(self.skill_index_budget, bool)
            or self.skill_index_budget < 1
        ):
            raise ValueError("Skill trust and index budget are invalid")
        if not isinstance(self.sandbox, SandboxSettings):
            raise ValueError("Sandbox settings must be validated")
        if not self.workspace.is_absolute() or not self.user_data.is_absolute():
            raise ValueError("Application paths must be absolute")
        if (
            not isinstance(self.summary_keep_recent_tokens, int)
            or isinstance(self.summary_keep_recent_tokens, bool)
            or self.summary_keep_recent_tokens < 1
        ):
            raise ValueError("Summary configuration is invalid")


def load_config(workspace: str | None = None, user_data: str | None = None) -> AppConfig:
    project = Path(workspace).expanduser().resolve() if workspace else Path.cwd().resolve()
    data = Path(user_data).expanduser().resolve() if user_data else Path.home() / ".pycode"
    return AppConfig(workspace=project, user_data=data)


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


def credential_environment(
    config: AppConfig, providers: tuple[ProviderConfig, ...]
) -> dict[str, str]:
    environment = dict(os.environ)
    references = {
        provider.api_key_env for provider in providers if not environment.get(provider.api_key_env)
    }
    path = (config.credential_workspace or config.workspace) / ".env"
    if not references or not path.exists():
        return environment
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        name, value = line.split("=", 1)
        name = name.strip()
        if name not in references:
            continue
        value = value.strip().strip('"').strip("'")
        if value:
            environment[name] = value
    return environment


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
