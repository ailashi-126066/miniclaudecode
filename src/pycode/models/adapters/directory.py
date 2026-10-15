"""Use public provider SDK model-list APIs, including their pagination."""

from collections.abc import Mapping

import anthropic
import openai

from pycode.kernel import ModelCapabilities, ModelError
from pycode.models.domain.models import DiscoveredModel, ProviderConfig
from pycode.models.ports.models import ModelFailureClassifier


class SDKModelDirectory:
    def __init__(self, credentials: Mapping[str, str], classifier: ModelFailureClassifier) -> None:
        self._credentials = credentials
        self._classifier = classifier

    async def list_models(self, config: ProviderConfig) -> tuple[DiscoveredModel, ...]:
        key = self._credentials.get(config.api_key_env)
        if not key:
            raise ModelError("missing_api_key", f"API key reference is unset: {config.api_key_env}")
        options = {
            "api_key": key,
            "base_url": config.base_url,
            "timeout": config.timeout_seconds,
            "max_retries": 0,
            "default_headers": {"User-Agent": "pycode/0.1.0"},
        }
        client = (
            anthropic.AsyncAnthropic(**options)
            if config.implementation == "anthropic"
            else openai.AsyncOpenAI(**options)
        )
        try:
            async with client:
                page = await client.models.list()
                discovered = []
                async for model in page:
                    data = model.model_dump()
                    window = self.positive_integer(data.get("context_window"))
                    output = self.positive_integer(data.get("max_output_tokens"))
                    capabilities = ModelCapabilities(
                        context_window=window,
                        max_output_tokens=output,
                        supports_tools=data.get("supports_tools") is True,
                        supports_thinking=data.get("supports_thinking") is True,
                        supports_images=data.get("supports_images") is True,
                    )
                    discovered.append(DiscoveredModel(model.id, capabilities))
                return tuple(sorted(discovered, key=lambda item: item.model_id))
        except Exception as error:
            failure = self._classifier.classify(error)
            if failure is None:
                raise
            raise ModelError(failure.code, failure.message, retryable=failure.retryable) from error

    def positive_integer(self, value) -> int | None:
        if isinstance(value, int) and not isinstance(value, bool) and value > 0:
            return value
        return None
