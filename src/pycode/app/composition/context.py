"""Composition of context capabilities; concrete adapters stay at this boundary."""

from dataclasses import replace

from pycode.app.composition.models import build_background_model
from pycode.context.adapters.summary import SummaryWorkflow
from pycode.kernel import StorageError
from pycode.models.adapters.messages import stop_reason
from pycode.models.adapters.model_requests import model_request_step
from pycode.models.adapters.storage.requests import SQLiteRequestStore
from pycode.session.adapters.storage.layout import StorageLayout


def build_summary_generator(config, provider, consumers):
    def model_factory(selected, session_id, run_id, purpose):
        return build_background_model(config, selected, consumers, session_id, run_id, purpose)

    def records_factory():
        return SQLiteRequestStore(
            StorageLayout(config.workspace, config.user_data).runtime_database
        )

    return ConfiguredSummaryGenerator(provider, model_factory, records_factory)


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
            return await build_summary_workflow(owner.model).run(source, owner.requests)
        finally:
            await owner.aclose()


def agents_md_sources(config):
    """AGENTS.md is loaded only by MemoryMiddleware; project files require explicit trust."""
    sources = [config.user_data / "AGENTS.md"]
    if config.project_trusted:
        project = config.workspace / "AGENTS.md"
        if not project.resolve().is_relative_to(config.workspace.resolve()):
            raise StorageError(
                "instructions_outside_workspace", "Project AGENTS.md escapes the workspace"
            )
        sources.append(project)
    return tuple(dict.fromkeys(str(path.resolve()) for path in sources if path.is_file()))


def build_summary_workflow(model):
    return SummaryWorkflow(model, request_step=model_request_step, stop_reason=stop_reason)
