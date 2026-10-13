"""Observe SDK-decoded objects, preserving usage without parsing HTTP or SSE."""

import inspect

from pycode.core.models import ProviderKind
from pycode.core.values import clone_value


class ProviderObservation:
    def __init__(self, implementation: ProviderKind) -> None:
        self._implementation = implementation
        self.begin()

    def begin(self) -> None:
        self._usage = {}
        self._has_usage = False
        self._complete = False
        self._response_id = None
        self._assistant_reasoning = ()
        self._terminal = False
        self._status = None

    def prepare_reasoning(self, reasoning: tuple[str | None, ...]) -> None:
        self._assistant_reasoning = reasoning

    def request_options(self, options: dict) -> dict:
        if self._implementation != "deepseek" or "messages" not in options:
            return options
        messages = clone_value(options["messages"])
        index = 0
        for message in messages:
            if message.get("role") != "assistant":
                continue
            if index < len(self._assistant_reasoning):
                reasoning = self._assistant_reasoning[index]
                if reasoning is not None:
                    message["reasoning_content"] = reasoning
            index += 1
        return {**options, "messages": messages}

    def capture(self, value) -> None:
        if isinstance(value, dict):
            data = value
        elif hasattr(value, "model_dump"):
            data = value.model_dump(mode="json", exclude_none=True, exclude_unset=True)
        else:
            return
        event_type = data.get("type")
        delta = data.get("delta")
        delta_stop = delta.get("stop_reason") if isinstance(delta, dict) else None
        source = data
        if event_type == "message_start":
            source = data.get("message", {})
        elif event_type and event_type.startswith("response."):
            source = data.get("response", {})
        if isinstance(source.get("id"), str):
            self._response_id = source["id"]
        if isinstance(source.get("status"), str):
            self._status = source["status"]
        choices = source.get("choices", [])
        if any(choice.get("finish_reason") is not None for choice in choices):
            self._terminal = True
        if (
            event_type
            in {"message_stop", "response.completed", "response.failed", "response.incomplete"}
            or source.get("stop_reason") is not None
            or delta_stop is not None
        ):
            self._terminal = True
        usage = source.get("usage")
        if isinstance(usage, dict):
            self._has_usage = True
            self._usage.update(clone_value(usage))
            if event_type == "message_delta":
                self._complete = delta_stop is not None
            elif event_type and event_type.startswith("response."):
                self._complete = event_type in {
                    "response.completed",
                    "response.failed",
                    "response.incomplete",
                }
            elif event_type != "message_start":
                self._complete = True

    def snapshot(self) -> dict:
        metadata = {
            "provider_response_id": self._response_id,
            "pycode_stream_terminal": self._terminal,
            "pycode_provider_status": self._status,
        }
        if self._has_usage:
            metadata["pycode_provider_usage"] = clone_value(self._usage)
            metadata["pycode_usage_complete"] = self._complete
        return metadata

    def wrap(self, value):
        if hasattr(value, "__aiter__"):
            return ObservedAsyncStream(value, self)
        if hasattr(value, "__next__"):
            return ObservedStream(value, self)
        self.capture(value)
        return value


class ObservedAsyncStream:
    def __init__(self, stream, observation: ProviderObservation) -> None:
        self._stream = stream
        self._observation = observation

    async def __aiter__(self):
        try:
            async for item in self._stream:
                self._observation.capture(item)
                yield item
        finally:
            await self.close()

    async def close(self):
        await self._stream.close()

    async def __aenter__(self):
        await self._stream.__aenter__()
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        await self._stream.__aexit__(exc_type, exc, traceback)


class ObservedStream:
    def __init__(self, stream, observation: ProviderObservation) -> None:
        self._stream = stream
        self._observation = observation

    def __iter__(self):
        try:
            for item in self._stream:
                self._observation.capture(item)
                yield item
        finally:
            self._stream.close()

    def __enter__(self):
        self._stream.__enter__()
        return self

    def __exit__(self, exc_type, exc, traceback):
        return self._stream.__exit__(exc_type, exc, traceback)


class ObservedRawResponse:
    def __init__(self, response, observation: ProviderObservation) -> None:
        self._response = response
        self._observation = observation

    @property
    def headers(self):
        return self._response.headers

    @property
    def status_code(self):
        return self._response.status_code

    def parse(self, *args, **kwargs):
        parsed = self._response.parse(*args, **kwargs)
        if inspect.isawaitable(parsed):
            return self.parse_async(parsed)
        return self._observation.wrap(parsed)

    async def parse_async(self, parsed):
        return self._observation.wrap(await parsed)


class ObservedAsyncResource:
    def __init__(self, resource, observation: ProviderObservation, *, raw=False) -> None:
        self._resource = resource
        self._observation = observation
        self._raw = raw

    @property
    def with_raw_response(self):
        return ObservedAsyncResource(self._resource.with_raw_response, self._observation, raw=True)

    async def create(self, **kwargs):
        result = await self._resource.create(**self._observation.request_options(kwargs))
        if self._raw:
            return ObservedRawResponse(result, self._observation)
        return self._observation.wrap(result)


class ObservedResource:
    def __init__(self, resource, observation: ProviderObservation, *, raw=False) -> None:
        self._resource = resource
        self._observation = observation
        self._raw = raw

    @property
    def with_raw_response(self):
        return ObservedResource(self._resource.with_raw_response, self._observation, raw=True)

    def create(self, **kwargs):
        result = self._resource.create(**self._observation.request_options(kwargs))
        if self._raw:
            return ObservedRawResponse(result, self._observation)
        return self._observation.wrap(result)


class ObservedOpenAIRoot:
    def __init__(self, root, observation: ProviderObservation, *, asynchronous: bool) -> None:
        self.chat = root.chat
        self.beta = root.beta
        self.responses = (
            ObservedAsyncResource(root.responses, observation)
            if asynchronous
            else ObservedResource(root.responses, observation)
        )


class ObservedAnthropicRoot:
    def __init__(self, root, observation: ProviderObservation, *, asynchronous: bool) -> None:
        self.beta = root.beta
        self.messages = (
            ObservedAsyncResource(root.messages, observation)
            if asynchronous
            else ObservedResource(root.messages, observation)
        )
