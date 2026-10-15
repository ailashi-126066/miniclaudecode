"""Connect the Memory module to the selected Models module for one application."""

from pycode.app.composition.modules import default_modules


def build_memory_service(config, history, provider=None, consumers=(), *, modules=None):
    modules = default_modules(config) if modules is None else modules
    updater = (
        build_memory_updater(config, provider, consumers, modules=modules)
        if provider is not None
        else None
    )
    return modules.memory.service(history, updater=updater)


def build_memory_updater(config, provider, consumers=(), *, modules=None):
    modules = default_modules(config) if modules is None else modules

    async def owner_factory(selected, session_id, run_id, purpose, **options):
        return await modules.models.owner(
            selected, consumers, session_id, run_id, purpose, **options
        )

    def facade_factory(owner, prefix, **options):
        return modules.models.metered(owner, logical_prefix=prefix, **options)

    return modules.memory.updater(provider, owner_factory, facade_factory)
