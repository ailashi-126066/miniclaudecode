"""Per-attempt accounting and retries around the public LangChain model handler."""

import asyncio
from dataclasses import dataclass, replace
from uuid import uuid4

from langchain.agents.middleware import ExtendedModelResponse, ModelResponse, ModelRetryMiddleware
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.runnables import RunnableLambda

from pycode.core.errors import ModelError
from pycode.core.messages import Purpose
from pycode.core.models import ModelSelection
from pycode.core.requests import ModelFailure, RequestRecord, RetryPolicy
from pycode.core.values import plain_data
from pycode.ports.events import EventSink
from pycode.ports.models import ModelFailureClassifier, ModelObservation, RequestStore
from pycode.ports.timing import Timing
from pycode.workflows.messages import normalize_usage


@dataclass(frozen=True)
class ModelMessages:
    messages: list[BaseMessage]


@dataclass
class AttemptCounter:
    value: int = 0


def retryable_model_error(error):
    return isinstance(error, ModelError) and error.retryable


def model_request_step(requests, handler, *, logical_id):
    """Expose the existing retry/accounting boundary as one LCEL step."""

    async def invoke(request):
        return await requests.invoke(request, handler, logical_id=logical_id)

    return RunnableLambda(invoke, name="pycode_model_request")


class ModelRequestRunner:
    @property
    def model_signature(self):
        from pycode.core.summary_source import data_hash

        return data_hash(plain_data(self._selection))

    def __init__(
        self,
        store: RequestStore,
        classifier: ModelFailureClassifier,
        timing: Timing,
        policy: RetryPolicy,
        events: EventSink,
        selection: ModelSelection,
        session_id: str,
        run_id: str,
        *,
        agent_id: str = "lead",
        purpose: Purpose = "agent",
        observation: ModelObservation | None = None,
        candidate_index: int = 0,
    ) -> None:
        self._store = store
        self._classifier = classifier
        self._timing = timing
        self._policy = policy
        self._events = events
        self._selection = selection
        self._session_id = session_id
        self._run_id = run_id
        self._agent_id = agent_id
        self._purpose = purpose
        self._observation = observation
        self.candidate_index = candidate_index
        self.active_request_id: str | None = None
        self._invocation_lock = asyncio.Lock()

    @property
    def selection(self):
        return self._selection

    async def invoke(self, request, handler, *, logical_id: str | None = None):
        # A Runner and its SDK observation belong to one request at a time.
        # Native components may issue concurrent queries within the same job.
        async with self._invocation_lock:
            return await self._invoke(request, handler, logical_id=logical_id)

    async def _invoke(self, request, handler, *, logical_id: str | None = None):
        offset = 0
        if logical_id is not None:
            previous = [
                record
                for record in await self._store.list_requests(self._session_id)
                if record.logical_id == logical_id
                and record.run_id == self._run_id
                and record.candidate_index == self.candidate_index
                and record.provider == self._selection.provider
                and record.model_id == self._selection.model_id
            ]
            if previous:
                offset = max(record.attempt for record in previous) + 1
        attempts = AttemptCounter()

        async def metered_attempt(current):
            index = attempts.value
            attempts.value += 1
            try:
                return await self.invoke_attempt(current, handler, logical_id, offset + index)
            except ModelError as error:
                if error.retryable and index < self._policy.max_retries:
                    # LangChain owns retry scheduling. Accounting owns the header-
                    # aware delay so Retry-After is honored for every provider.
                    delay = self._policy.delay(index + 1, error.retry_after)
                    await self._events.emit(
                        "retry",
                        {
                            "request_id": error.request_id,
                            "attempt": index + 1,
                            "max_retries": self._policy.max_retries,
                            "delay_seconds": delay,
                            "error_code": error.code,
                        },
                    )
                    await self._timing.sleep(delay)
                raise

        retry = ModelRetryMiddleware(
            max_retries=self._policy.max_retries,
            retry_on=retryable_model_error,
            on_failure="error",
            initial_delay=0,
            jitter=False,
        )
        return await retry.awrap_model_call(request, metered_attempt)

    async def invoke_attempt(self, request, handler, logical_id, attempt):
        record = RequestRecord(
            request_id=str(uuid4()),
            session_id=self._session_id,
            run_id=self._run_id,
            provider=self._selection.provider,
            model_id=self._selection.model_id,
            purpose=self._purpose if attempt == 0 else "retry",
            attempt=attempt,
            status="started",
            started_at=self._timing.now(),
            agent_id=self._agent_id,
            logical_id=logical_id,
            candidate_index=self.candidate_index,
        )
        await self._store.start(record)
        if self._observation is not None:
            self._observation.begin()
            reasoning = tuple(
                message.additional_kwargs.get("reasoning_content")
                for message in request.messages
                if isinstance(message, AIMessage)
            )
            self._observation.prepare_reasoning(reasoning)
        self.active_request_id = record.request_id
        await self._events.emit("model_request_start", plain_data(record))
        failure = None
        try:
            result = await handler(request)
            response = (
                result.model_response if isinstance(result, ExtendedModelResponse) else result
            )
            if not isinstance(response, ModelResponse) or not isinstance(
                response.result[-1], AIMessage
            ):
                raise TypeError("Model handler did not return a completed assistant response")
            assistant = response.result[-1]
            response_metadata = dict(assistant.response_metadata)
            if self._observation is not None:
                response_metadata.update(self._observation.snapshot())
            failure = self._classifier.response_failure(response_metadata)
        except asyncio.CancelledError:
            await self.settle_failure(
                record,
                ModelFailure("model_interrupted", "Model request cancelled", False),
                status="interrupted",
            )
            raise
        except Exception as error:
            failure = self._classifier.classify(error)
            if failure is None:
                await self.settle_failure(
                    record,
                    ModelFailure(
                        "model_implementation_error",
                        "Model implementation failed",
                        False,
                    ),
                )
                raise
        finally:
            self.active_request_id = None
        if failure is None:
            metadata = {
                **assistant.response_metadata,
                "pycode_request_id": record.request_id,
                "pycode_request_purpose": record.purpose,
                "pycode_agent_id": record.agent_id,
                "pycode_origin_purpose": self._purpose,
                "pycode_candidate_index": self.candidate_index,
            }
            if self._observation is not None:
                metadata.update(self._observation.snapshot())
            assistant = assistant.model_copy(update={"response_metadata": metadata}, deep=True)
            usage = normalize_usage(assistant, self._selection, record.session_id, record.run_id)
            completed = replace(
                record,
                status="completed",
                settled_at=self._timing.now(),
                usage=usage,
                provider_request_id=metadata.get("provider_response_id") or assistant.id,
            )
            await self._store.settle(completed)
            await self._events.emit("usage", plain_data(usage))
            await self._events.emit("model_request_end", plain_data(completed))
            updated = replace(response, result=[*response.result[:-1], assistant])
            return (
                replace(result, model_response=updated)
                if isinstance(result, ExtendedModelResponse)
                else updated
            )
        await self.settle_failure(record, failure)
        raise ModelError(
            failure.code,
            failure.message,
            retryable=failure.retryable,
            retry_after=failure.retry_after,
            request_id=record.request_id,
        )

    async def settle_failure(
        self, record: RequestRecord, failure: ModelFailure, *, status="failed"
    ) -> None:
        metadata = {
            **failure.metadata,
            "pycode_request_id": record.request_id,
            "pycode_request_purpose": record.purpose,
            "pycode_agent_id": record.agent_id,
            "pycode_origin_purpose": self._purpose,
            "pycode_candidate_index": self.candidate_index,
        }
        if self._observation is not None:
            observed = self._observation.snapshot()
            if status == "interrupted":
                observed["pycode_usage_complete"] = False
            metadata.update(observed)
            if isinstance(failure.metadata.get("usage"), dict):
                metadata["pycode_provider_usage"] = failure.metadata["usage"]
                metadata["pycode_usage_complete"] = True
        usage = normalize_usage(
            AIMessage(content="", response_metadata=metadata),
            self._selection,
            record.session_id,
            record.run_id,
        )
        usage = replace(usage, error=failure.code)
        settled = replace(
            record,
            status=status,
            settled_at=self._timing.now(),
            error_code=failure.code,
            usage=usage,
            provider_request_id=metadata.get("provider_request_id"),
        )
        await self._store.settle(settled)
        await self._events.emit("usage", plain_data(usage))
        await self._events.emit("model_request_end", plain_data(settled))

    async def list_attempts(self):
        return tuple(
            record
            for record in await self._store.list_requests(self._session_id)
            if record.run_id == self._run_id
        )
