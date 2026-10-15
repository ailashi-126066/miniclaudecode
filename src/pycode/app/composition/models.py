"""Composition of models capabilities; concrete adapters stay at this boundary."""

from dataclasses import dataclass

from pycode.app.config import AppConfig, credential_environment
from pycode.context import ContextProjector
from pycode.kernel import EventStream, MemoryEventJournal, SystemTiming
from pycode.models import ModelCatalog, ModelControl, ProviderConfig, RetryPolicy
from pycode.models.adapters.directory import SDKModelDirectory
from pycode.models.adapters.factory import ModelFactory
from pycode.models.adapters.failures import ProviderFailureClassifier
from pycode.models.adapters.model_requests import ModelRequestRunner
from pycode.models.adapters.observation import ProviderObservation
from pycode.models.adapters.storage.catalog import FileCatalogStore
from pycode.models.adapters.storage.requests import SQLiteRequestStore
from pycode.session.adapters.storage.layout import StorageLayout


def build_model_catalog(config: AppConfig, providers: tuple[ProviderConfig, ...]) -> ModelCatalog:
    timing = SystemTiming()
    directory = SDKModelDirectory(
        credential_environment(config, providers), ProviderFailureClassifier(timing)
    )
    store = FileCatalogStore(config.user_data / "model-catalog.json")
    return ModelCatalog(providers, directory, store, timing)


def build_model_control(catalog: ModelCatalog, store=None) -> ModelControl:
    return ModelControl(catalog, ContextProjector(), store)


@dataclass
class MeteredModelOwner:
    model: object
    requests: object
    factory: object
    records: object
    events: object

    async def aclose(self):
        try:
            await self.factory.aclose()
        finally:
            try:
                await self.records.aclose()
            finally:
                await self.events.aclose()


def build_background_model(
    config,
    provider,
    consumers,
    session_id,
    run_id,
    purpose,
    *,
    retry_policy=None,
    candidate_index=0,
):
    timing = SystemTiming()
    observation = ProviderObservation(provider.implementation)
    factory = ModelFactory(credential_environment(config, (provider,)), observation)
    model = factory.create(provider)
    records = SQLiteRequestStore(StorageLayout(config.workspace, config.user_data).runtime_database)
    journal = MemoryEventJournal()
    events = EventStream(session_id, run_id, consumers, journal=journal)
    requests = ModelRequestRunner(
        records,
        ProviderFailureClassifier(timing),
        timing,
        retry_policy or RetryPolicy(),
        events,
        factory.selection(provider),
        session_id,
        run_id,
        purpose=purpose,
        observation=observation,
        candidate_index=candidate_index,
    )
    return MeteredModelOwner(model, requests, factory, records, journal)
