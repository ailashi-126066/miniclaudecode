"""Resolve referenced Provider credentials from process environment and the configured dotenv."""

import os

from pycode.app.configuration.settings import AppConfig
from pycode.models import ProviderConfig


def credential_environment(
    config: AppConfig, providers: tuple[ProviderConfig, ...]
) -> dict[str, str]:
    environment = dict(os.environ)
    references = {
        provider.api_key_env for provider in providers if not environment.get(provider.api_key_env)
    }
    path = (config.credential_workspace or config.workspace) / ".env"
    if not references or not path.exists():
        return environment
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        name, value = line.split("=", 1)
        name = name.strip()
        if name not in references:
            continue
        value = value.strip().strip('"').strip("'")
        if value:
            environment[name] = value
    return environment
