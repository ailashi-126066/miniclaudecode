"""Select public LangChain integrations; never implement a parallel HTTP model client."""

from collections.abc import Mapping
from typing import Any

import anthropic
import openai
from langchain_anthropic import ChatAnthropic
from langchain_core.language_models import BaseChatModel
from langchain_deepseek import ChatDeepSeek
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from pycode.adapters.models.anthropic import ManagedChatAnthropic
from pycode.adapters.models.observation import (
    ObservedAnthropicRoot,
    ObservedAsyncResource,
    ObservedOpenAIRoot,
    ObservedResource,
    ProviderObservation,
)
from pycode.core.errors import ModelError
from pycode.core.models import ModelSelection, ProviderConfig, model_selection


class ModelFactory:
    def __init__(
        self, credentials: Mapping[str, str], observation: ProviderObservation | None = None
    ) -> None:
        self._credentials = credentials
        self._observation = observation
        self._owned_clients = []
        self._owned_async_clients = []

    def create(self, config: ProviderConfig) -> BaseChatModel:
        key = self._credentials.get(config.api_key_env)
        if not key:
            raise ModelError(
                "missing_api_key", f"API key environment reference is unset: {config.api_key_env}"
            )
        if config.thinking and not config.supports_thinking:
            raise ModelError(
                "unsupported_thinking", f"Thinking capability is not declared for {config.name}"
            )
        common: dict[str, Any] = {
            "model": config.model_id,
            "api_key": SecretStr(key),
            "base_url": config.base_url,
            "max_tokens": config.max_output_tokens,
            "timeout": config.timeout_seconds,
            "max_retries": 0,
            "streaming": True,
            "stream_usage": True,
            "default_headers": {"User-Agent": "pycode/0.1.0"},
        }
        if config.reasoning_effort is not None:
            common["reasoning_effort"] = config.reasoning_effort
        if self._observation is not None and config.implementation in {"deepseek", "openai"}:
            sdk_options = {
                "api_key": key,
                "base_url": config.base_url,
                "max_retries": 0,
                "timeout": config.timeout_seconds,
                "default_headers": common["default_headers"],
            }
            root = openai.OpenAI(**sdk_options)
            asynchronous = openai.AsyncOpenAI(**sdk_options)
            self._owned_clients.append(root)
            self._owned_async_clients.append(asynchronous)
            common.update(
                client=ObservedResource(root.chat.completions, self._observation),
                async_client=ObservedAsyncResource(
                    asynchronous.chat.completions, self._observation
                ),
                root_client=ObservedOpenAIRoot(root, self._observation, asynchronous=False),
                root_async_client=ObservedOpenAIRoot(
                    asynchronous, self._observation, asynchronous=True
                ),
            )
        if config.implementation == "deepseek":
            common["extra_body"] = {"thinking": dict(config.thinking) or {"type": "disabled"}}
            return ChatDeepSeek(**common)
        if config.implementation == "openai":
            if config.openai_api is None:
                raise ModelError(
                    "missing_api_mode", "OpenAI Provider must explicitly select an API mode"
                )
            common["use_responses_api"] = config.openai_api == "responses"
            if config.thinking:
                if config.openai_api == "responses":
                    common["reasoning"] = dict(config.thinking)
                else:
                    common["reasoning_effort"] = config.thinking.get("effort")
            return ChatOpenAI(**common)
        if config.implementation == "anthropic":
            if config.thinking:
                common["thinking"] = dict(config.thinking)
            if self._observation is None:
                return ChatAnthropic(**common)
            sdk_options = {
                "api_key": key,
                "base_url": config.base_url,
                "max_retries": 0,
                "timeout": config.timeout_seconds,
                "default_headers": common["default_headers"],
            }
            root = anthropic.Anthropic(**sdk_options)
            asynchronous = anthropic.AsyncAnthropic(**sdk_options)
            self._owned_clients.append(root)
            self._owned_async_clients.append(asynchronous)
            return ManagedChatAnthropic(
                **common,
                sdk_client=ObservedAnthropicRoot(root, self._observation, asynchronous=False),
                sdk_async_client=ObservedAnthropicRoot(
                    asynchronous, self._observation, asynchronous=True
                ),
            )
        raise ModelError("unknown_provider", f"Unknown implementation: {config.implementation}")

    def selection(self, config: ProviderConfig) -> ModelSelection:
        return model_selection(config)

    async def aclose(self) -> None:
        for client in self._owned_clients:
            client.close()
        for client in self._owned_async_clients:
            await client.close()
        self._owned_clients.clear()
        self._owned_async_clients.clear()
