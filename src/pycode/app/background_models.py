"""Owned one-request background model lifetimes; construction remains injected."""

from dataclasses import replace

from pycode.core.context_budget import InputBudget
from pycode.workflows.memory import MemoryWorkflow
from pycode.workflows.summary import SummaryWorkflow


class ConfiguredSummaryGenerator:
    def __init__(self, provider, model_factory, records_factory):
        self.provider = replace(
            provider, supports_tools=False, max_output_tokens=min(provider.max_output_tokens, 4096)
        )
        self.model_factory = model_factory
        self.records_factory = records_factory

    async def reconcile_lost(self, source):
        records = self.records_factory()
        try:
            await records.interrupt_unsettled(source.session_id, source.job_id)
        finally:
            await records.aclose()

    async def generate(self, source):
        owner = self.model_factory(self.provider, source.session_id, source.job_id, "summary")
        try:
            return await SummaryWorkflow(owner.model).run(source, owner.requests)
        finally:
            await owner.aclose()


class ConfiguredMemoryExtractor:
    def __init__(self, provider, model_factory):
        self.provider = replace(
            provider, supports_tools=False, max_output_tokens=min(provider.max_output_tokens, 4096)
        )
        self.model_id = provider.model_id
        self.model_factory = model_factory

    async def extract(self, snapshot, run_id, *, full=False):
        owner = self.model_factory(
            self.provider, snapshot.header.session_id, run_id, "memory_extract"
        )
        try:
            budget = InputBudget.create(
                self.provider.context_window, self.provider.max_output_tokens
            )
            return await MemoryWorkflow(
                owner.model, owner.requests, input_limit=min(16000, budget.input_limit)
            ).extract(snapshot, run_id, full=full)
        finally:
            await owner.aclose()
