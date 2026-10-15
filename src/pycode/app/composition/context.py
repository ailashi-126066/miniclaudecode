"""Connect context generation to the chosen model owners and request records."""

from pycode.app.composition.modules import default_modules


def build_summary_generator(config, provider, consumers, *, modules=None):
    modules = default_modules(config) if modules is None else modules

    async def owner_factory(selected, session_id, run_id, purpose):
        return await modules.models.owner(selected, consumers, session_id, run_id, purpose)

    return modules.context.generator(provider, owner_factory, modules.models.records)
