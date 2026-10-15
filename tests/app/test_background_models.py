"""Background memory models always run with thinking off and one owned model lifetime."""

from dataclasses import replace
from types import SimpleNamespace

from pycode.app.bootstrap import compose_application
from pycode.app.config import AppConfig
from pycode.memory.adapters.background import ConfiguredMemoryUpdater
from pycode.memory.domain.memory import MemoryRecord
from pycode.models.module import ModelsModule
from tests.support.execution import provider


def metered_facade(owner, prefix, **options):
    return ModelsModule.metered(owner, logical_prefix=prefix, **options)


async def unused_owner(*args):
    raise AssertionError("This configuration check must not create a model")


def test_memory_updater_forces_thinking_off_for_forced_tool_choice():
    configured = replace(
        provider("http://127.0.0.1:9"),
        implementation="deepseek",
        supports_thinking=True,
        thinking={"type": "enabled"},
        reasoning_effort="high",
        max_output_tokens=8192,
    )
    updater = ConfiguredMemoryUpdater(configured, unused_owner, metered_facade)
    assert updater.provider.thinking == {} and updater.provider.reasoning_effort is None
    assert updater.provider.max_output_tokens == 4096
    owner = SimpleNamespace(model=object(), requests=object())
    model = updater.facade(owner, "memory_manager")
    assert model.supports_forced_tool_choice and model.logical_prefix == "memory_manager"


async def test_selection_keeps_only_known_ids_in_score_order(monkeypatch):
    closed = []

    class Owner:
        model = object()
        requests = object()

        async def aclose(self):
            closed.append(True)

    class Selector:
        def __init__(self, model):
            pass

        async def __call__(self, query, directory):
            assert [entry["id"] for entry in directory] == ["user:a", "project:b"]
            return [
                {"id": "project:b", "score": 0.4},
                {"id": "user:missing", "score": 0.9},
                {"id": "user:a", "score": 0.8},
            ]

    monkeypatch.setattr("pycode.memory.adapters.background.MemoryDirectorySelector", Selector)
    purposes = []

    async def factory(selected, session_id, run_id, purpose):
        purposes.append((selected.thinking, purpose))
        return Owner()

    updater = ConfiguredMemoryUpdater(provider("http://127.0.0.1:9"), factory, metered_facade)
    records = [
        MemoryRecord(("memories", "user", "u"), "a", "user", "Memory", "A", "t", "t"),
        MemoryRecord(("memories", "project", "p"), "b", "project", "Memory", "B", "t", "t"),
    ]
    chosen = await updater.select("s", "r", "query", records, limit=5)
    assert chosen == [("user", "a"), ("project", "b")]
    assert purposes == [({}, "memory_select")] and closed == [True]


async def test_selection_considers_older_entries_beyond_first_directory_batch(monkeypatch):
    batches = []

    class Owner:
        model = object()
        requests = object()

        async def aclose(self):
            pass

    class Selector:
        def __init__(self, model):
            pass

        async def __call__(self, query, directory):
            batches.append(len(directory))
            return [{"id": directory[-1]["id"], "score": len(batches) / 10}]

    monkeypatch.setattr("pycode.memory.adapters.background.MemoryDirectorySelector", Selector)

    async def factory(*args):
        return Owner()

    updater = ConfiguredMemoryUpdater(provider("http://127.0.0.1:9"), factory, metered_facade)
    records = [
        MemoryRecord(("memories", "user", "u"), str(i), "user", "Memory", "A", "t", "t")
        for i in range(150)
    ]
    assert await updater.select("s", "r", "query", records, limit=1) == [("user", "149")]
    assert batches == [100, 50]


async def test_memory_model_factory_disables_request_retries(tmp_path, monkeypatch):
    policies = []

    async def factory(*args, **kwargs):
        policies.append(kwargs["retry_policy"])
        return None

    application = compose_application(AppConfig(tmp_path, tmp_path / "data"))
    monkeypatch.setattr(application.modules.models, "owner", factory)
    updater = application.memory_updater(provider("http://127.0.0.1:9"))
    await updater.model_factory(updater.provider, "s", "r", "memory_extract")
    assert len(policies) == 1 and policies[0].max_retries == 0
