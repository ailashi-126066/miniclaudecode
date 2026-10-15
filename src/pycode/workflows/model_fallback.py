"""The official fallback owns switching; each actual candidate uses existing retries/accounting."""

import asyncio
from dataclasses import dataclass

from langchain.agents.middleware import ModelFallbackMiddleware
from langchain_core.language_models import BaseChatModel
from langgraph.errors import GraphBubbleUp

from pycode.core.errors import ModelError, StorageError
from pycode.core.models import ModelReference, ProviderConfig, model_selection
from pycode.core.session import EntryDraft
from pycode.workflows.boundaries import calibrate_model_request
from pycode.workflows.messages import to_framework
from pycode.workflows.model_requests import ModelRequestRunner

ALLOWED_FAILURES = frozenset(
    {
        "provider_connection",
        "provider_timeout",
        "provider_stream_error",
        "provider_rate_limit",
        "provider_http_500",
        "provider_http_502",
        "provider_http_503",
        "provider_http_504",
        "provider_http_529",
    }
)


class StopFallback(GraphBubbleUp):
    def __init__(self, error):
        self.error = error


@dataclass(frozen=True)
class ModelCandidate:
    model: BaseChatModel
    provider: ProviderConfig
    requests: ModelRequestRunner
    index: int


class ModelFallback:
    def __init__(self, primary, references, resolve, owner_factory, configure):
        self.primary = primary
        self.references = references
        self.resolve = resolve
        self.owner_factory = owner_factory
        self.configure = configure
        self.owners = {}

    def activate(self, context, candidate):
        context.requests = candidate.requests
        self.configure(context, candidate.provider)
        context.selection = model_selection(candidate.provider)

    def restore_primary(self, context):
        self.activate(context, self.primary)

    async def invoke(self, request, handler, *, logical_id):
        context = request.runtime.context
        self.activate(context, self.primary)
        request = request.override(model=self.primary.model)
        try:
            return await self.primary.requests.invoke(request, handler, logical_id=logical_id)
        except GraphBubbleUp:
            raise
        except Exception as primary_error:
            if (
                not isinstance(primary_error, ModelError)
                or primary_error.code not in ALLOWED_FAILURES
            ):
                raise
            original_error = primary_error
        # Build models only when a permitted primary failure needs them. The
        # official primary handler replays that error, never the completed SDK call.
        candidates = []
        for index, reference in enumerate(self.references, start=1):
            try:
                if self.resolve is None:
                    raise ModelError(
                        "fallback_model_unavailable", "No configured fallback model resolver"
                    )
                provider = self.resolve(reference.provider, reference.model_id)
                reason = self.incompatibility(provider, request)
                if reason is not None:
                    await self.record(context, logical_id, index, provider, "skipped", reason)
                    continue
                owner = self.owners.get(index)
                if owner is None:
                    owner = self.owner_factory(provider, context, index)
                    self.owners[index] = owner
                candidates.append(ModelCandidate(owner.model, provider, owner.requests, index))
            except ModelError as error:
                await self.record(context, logical_id, index, reference, "skipped", error.code)
        if not candidates:
            raise original_error
        by_model = {id(candidate.model): candidate for candidate in candidates}

        async def attempt(current):
            candidate = by_model.get(id(current.model))
            if candidate is None:
                raise original_error
            try:
                self.activate(context, candidate)
                await self.record(
                    context, logical_id, candidate.index, candidate.provider, "attempt", None
                )
                if context.history_store is not None:
                    snapshot = await context.history_store.snapshot()
                    projection = context.projector.project(snapshot)
                    current = current.override(
                        messages=[
                            to_framework(message, context.selection)
                            for message in projection.messages
                        ]
                    )
                if not context.skill_boundary:
                    current = await calibrate_model_request(current)
                response = await candidate.requests.invoke(current, handler, logical_id=logical_id)
                await self.record(
                    context, logical_id, candidate.index, candidate.provider, "completed", None
                )
                return response
            except GraphBubbleUp:
                raise
            except Exception as error:
                if not isinstance(error, StorageError):
                    reason = (
                        error.code if isinstance(error, ModelError) else "candidate_execution_error"
                    )
                    await self.record(
                        context, logical_id, candidate.index, candidate.provider, "failed", reason
                    )
                if not isinstance(error, ModelError) or error.code not in ALLOWED_FAILURES:
                    raise StopFallback(error) from error
                raise

        fallback = ModelFallbackMiddleware(
            self.primary.model, *(candidate.model for candidate in candidates)
        )
        try:
            return await fallback.awrap_model_call(request, attempt)
        except StopFallback as stopped:
            raise stopped.error from None

    def incompatibility(self, provider, request):
        if request.tools and not provider.supports_tools:
            return "fallback_tools_unsupported"
        if (
            self.primary.provider.thinking or self.primary.provider.reasoning_effort
        ) and not provider.supports_thinking:
            return "fallback_thinking_unsupported"
        for message in request.messages:
            if isinstance(message.content, list):
                for block in message.content:
                    if (
                        isinstance(block, dict)
                        and block.get("type") in {"image", "image_url", "input_image"}
                        and not provider.supports_images
                    ):
                        return "fallback_images_unsupported"
        for tool in request.tools:
            if (
                isinstance(tool, dict)
                and tool.get("type") not in {"function"}
                and provider.implementation != self.primary.provider.implementation
            ):
                return "fallback_provider_tool_unsupported"
        return None

    async def record(
        self, context, logical_id, index, provider: ProviderConfig | ModelReference, status, reason
    ):
        name = provider.name if isinstance(provider, ProviderConfig) else provider.provider
        data = {
            "kind": "model_candidate",
            "logical_id": logical_id,
            "candidate_index": index,
            "provider": name,
            "model_id": provider.model_id,
            "status": status,
            "error_code": reason,
        }
        if context.history_store is not None:
            await context.history_store.append_entry(
                EntryDraft("custom", data=data, run_id=context.run_id)
            )
        await context.events.emit("notification", data)

    async def aclose(self):
        owners = tuple(self.owners.values())
        self.owners.clear()
        results = await asyncio.gather(
            *(owner.aclose() for owner in owners), return_exceptions=True
        )
        for result in results:
            if isinstance(result, BaseException):
                raise result
