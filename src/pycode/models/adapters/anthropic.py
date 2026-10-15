"""Keep the native ChatAnthropic protocol, supplying owned observed SDK clients.

The pinned integration does not expose client injection as a constructor field.
Only its two lazy client accessors are overridden. HTTP, SSE decoding, message
conversion, thinking and tool binding remain the official implementations.
See the real-wire regression and docs/source-map.md for the affected versions.
"""

from typing import Any

from langchain_anthropic import ChatAnthropic
from pydantic import Field


class ManagedChatAnthropic(ChatAnthropic):
    sdk_client: Any = Field(exclude=True, repr=False)
    sdk_async_client: Any = Field(exclude=True, repr=False)

    @property
    def _client(self):
        return self.sdk_client

    @property
    def _async_client(self):
        return self.sdk_async_client
