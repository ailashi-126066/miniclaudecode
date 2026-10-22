"""Build the application without opening connections or creating active sessions."""

from pycode.app.api import Application
from pycode.app.composition.modules import ApplicationModules, default_modules
from pycode.app.configuration.settings import AppConfig
from pycode.models import ProviderConfig


def compose_application(
    config: AppConfig,
    providers: tuple[ProviderConfig, ...] = (),
    *,
    modules: ApplicationModules | None = None,
) -> Application:
    """Compose local capabilities; provider connections open only when requested."""
    return Application(config, providers, default_modules(config) if modules is None else modules)
