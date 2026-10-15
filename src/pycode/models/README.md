# Models

`module.ModelsModule` owns the model catalog store, SDK directory/factory, native message converters,
request records, retries, metering, tool selection, fallback and calibration wiring. Inputs are catalog
and request-database paths, a credential mapping or a provider-aware credential callback, and an
optional clock. The callback is resolved for each catalog/owner request, so an application with no
initial providers can still use an explicitly supplied provider later. No module is recreated by the
application to override a selected implementation.

```python
from pathlib import Path
from pycode.models.module import ModelsModule

models = ModelsModule(Path("state/model-catalog.json"), Path("state/runtime.sqlite3"), {})
catalog = models.catalog(())
```

`control(catalog, projector, store, events)` takes an explicit native projector. `records()` opens a
request store. `await owner(provider, consumers, session_id, run_id, purpose, ...)` supplies a
`MeteredModelOwner`: its model, runner, factory and records form one request lifetime. With an
external `events` sink the owner borrows it; otherwise it owns a bounded memory event journal.
`await owner.aclose()` closes SDK clients, then request records, then only its owned journal.
Callers that open a standalone `records()` store must close it. Failed owner construction awaits
closure of every resource it acquired before propagating the failure; borrowed event sinks remain
owned by the caller.

`metered`, `selector`, `candidate` and `fallback` compose these products inside Models.
`fallback(..., calibrator=...)` accepts run-specific native resource lookup calibration.
Framework converter/request callbacks are outputs of this assembly entry, not re-exports in the
feature's pure public API. The application connects them to Agent without Agent importing adapters.
