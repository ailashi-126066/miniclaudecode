"""Composition of memory capabilities; concrete adapters stay at this boundary."""

from dataclasses import replace

from pycode.app.composition.models import build_background_model
from pycode.context import InputBudget
from pycode.kernel import SystemTiming
from pycode.memory import MemoryService, automatic_namespaces, scoped_memory_namespaces
from pycode.memory.adapters.memory_query import MemoryDirectorySelector
from pycode.memory.adapters.memory_update import MemoryConsolidator
from pycode.memory.adapters.storage.json_memory import JsonMemoryStore, MemoryChangeLog
from pycode.models import RetryPolicy
from pycode.models.adapters.metered_model import MeteredChatModel
from pycode.session.adapters.storage.layout import StorageLayout


def memory_namespaces(config):
    project_id = StorageLayout(config.workspace, config.user_data).project.name
    return scoped_memory_namespaces(str(config.user_data.resolve()), project_id)


def build_memory_service(config, history, provider=None, consumers=()):
    user_namespace, project_namespace = memory_namespaces(config)
    roots = {
        user_namespace: config.user_data / "memory",
        project_namespace: config.workspace / ".pycode" / "memory",
    }
    scopes = {user_namespace: "user", project_namespace: "project"}
    # Automatic consolidation writes project memory only for a trusted project.
    automatic = automatic_namespaces(
        user_namespace, project_namespace, trusted=config.project_trusted
    )
    return MemoryService(
        history,
        JsonMemoryStore(roots),
        scopes,
        SystemTiming(),
        MemoryChangeLog(config.user_data / "memory" / "changes.jsonl"),
        updater=build_memory_updater(config, provider, consumers) if provider is not None else None,
        mode=config.memory_mode,
        auto_namespaces=automatic,
    )


def build_memory_updater(config, provider, consumers=()):
    def model_factory(selected, session_id, run_id, purpose):
        return build_background_model(
            config,
            selected,
            consumers,
            session_id,
            run_id,
            purpose,
            retry_policy=RetryPolicy(max_retries=0),
        )

    return ConfiguredMemoryUpdater(provider, model_factory)


class ConfiguredMemoryUpdater:
    """Every consolidation/selection owns one metered background model lifetime."""

    def __init__(self, provider, model_factory):
        # Thinking is always off for memory: LangMem and the selector force a tool choice, which
        # DeepSeek rejects with thinking enabled. Empty thinking makes the factory send
        # {"type": "disabled"} to DeepSeek and omit thinking/reasoning for other providers.
        self.provider = replace(
            provider,
            thinking={},
            reasoning_effort=None,
            max_output_tokens=min(provider.max_output_tokens, 4096),
        )
        self.model_factory = model_factory

    def facade(self, owner, prefix):
        budget = InputBudget.create(self.provider.context_window, self.provider.max_output_tokens)
        return MeteredChatModel(
            model=owner.model,
            requests=owner.requests,
            logical_prefix=prefix,
            input_limit=min(16000, budget.input_limit),
            max_calls=24,
        )

    async def consolidate(self, store, namespace, summary, messages, session_id, run_id):
        owner = self.model_factory(self.provider, session_id, run_id, "memory_extract")
        try:
            consolidator = MemoryConsolidator(self.facade(owner, "memory_manager"))
            return await consolidator(store, namespace, summary, messages)
        finally:
            await owner.aclose()

    async def select(self, session_id, run_id, query, records, *, limit=10):
        """Return (scope, memory_id) pairs the model judged relevant, best first."""
        owner = self.model_factory(self.provider, session_id, run_id, "memory_select")
        try:
            selector = MemoryDirectorySelector(self.facade(owner, "memory_recall"))
            # Include every candidate while keeping each request under the input limit.
            choices = []
            for offset in range(0, len(records), 100):
                directory = [
                    {"id": record.scope + ":" + record.memory_id, "summary": record.content[:120]}
                    for record in records[offset : offset + 100]
                ]
                allowed = {entry["id"] for entry in directory}
                choices.extend(
                    choice for choice in await selector(query, directory) if choice["id"] in allowed
                )
        finally:
            await owner.aclose()
        ranked = sorted(choices, key=lambda choice: -choice["score"])
        selected = []
        for choice in ranked:
            scope, memory_id = choice["id"].split(":", 1)
            identity = scope, memory_id
            if identity not in selected:
                selected.append(identity)
            if len(selected) >= limit:
                break
        return selected
