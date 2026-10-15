"""One owned model request scope for LangMem consolidation and recall selection."""

from dataclasses import replace

from pycode.context import InputBudget
from pycode.memory.adapters.memory_query import MemoryDirectorySelector
from pycode.memory.adapters.memory_update import MemoryConsolidator


class ConfiguredMemoryUpdater:
    """Every consolidation/selection owns one metered background model lifetime."""

    def __init__(self, provider, model_factory, facade_factory):
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
        self.facade_factory = facade_factory

    def facade(self, owner, prefix):
        budget = InputBudget.create(self.provider.context_window, self.provider.max_output_tokens)
        return self.facade_factory(
            owner,
            prefix,
            input_limit=min(16000, budget.input_limit),
            max_calls=24,
        )

    async def consolidate(self, store, namespace, summary, messages, session_id, run_id):
        owner = await self.model_factory(self.provider, session_id, run_id, "memory_extract")
        try:
            consolidator = MemoryConsolidator(self.facade(owner, "memory_manager"))
            return await consolidator(store, namespace, summary, messages)
        finally:
            await owner.aclose()

    async def select(self, session_id, run_id, query, records, *, limit=10):
        """Return (scope, memory_id) pairs the model judged relevant, best first."""
        owner = await self.model_factory(self.provider, session_id, run_id, "memory_select")
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
