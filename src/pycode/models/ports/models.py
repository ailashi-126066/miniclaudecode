"""Model failure and request-recording boundaries without framework message types."""

from typing import Any, Protocol

from pycode.models.domain.models import CatalogSnapshot, DiscoveredModel, ProviderConfig
from pycode.models.domain.requests import ModelFailure, RequestRecord


class ModelFailureClassifier(Protocol):
    def classify(self, error: Exception) -> ModelFailure | None: ...

    def response_failure(self, metadata: dict[str, Any]) -> ModelFailure | None: ...


class RequestStore(Protocol):
    async def start(self, record: RequestRecord) -> None: ...

    async def settle(self, record: RequestRecord) -> None: ...

    async def list_requests(self, session_id: str | None = None) -> tuple[RequestRecord, ...]: ...

    async def aclose(self) -> None: ...


class ModelObservation(Protocol):
    def begin(self) -> None: ...

    def snapshot(self) -> dict[str, Any]: ...

    def prepare_reasoning(self, reasoning: tuple[str | None, ...]) -> None: ...


class ModelDirectory(Protocol):
    async def list_models(self, config: ProviderConfig) -> tuple[DiscoveredModel, ...]: ...


class CatalogStore(Protocol):
    async def load(self) -> CatalogSnapshot | None: ...

    async def save(self, snapshot: CatalogSnapshot) -> None: ...
