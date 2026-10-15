"""Application scope for independent background-model lifetimes."""

from pycode.app.composition.modules import default_modules


async def build_background_model(
    config, provider, consumers, session_id, run_id, purpose, *, modules=None, **options
):
    modules = default_modules(config) if modules is None else modules
    return await modules.models.owner(provider, consumers, session_id, run_id, purpose, **options)
