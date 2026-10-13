"""Shared Provider and session fixtures for actual loopback framework tests."""

from pycode.app.container import open_agent_session
from pycode.core.models import ProviderConfig


def provider(url):
    return ProviderConfig(
        "test",
        "openai",
        "wire-model",
        url + "/v1",
        "PYCODE_RECOVERY_TEST_KEY",
        32000,
        4096,
        openai_api="chat_completions",
        supports_tools=True,
    )


async def session_at(config, configured, tool, *, create=False):
    return await open_agent_session(
        config, (configured,), "s1", create=create, tools=(tool,), refresh_models=False
    )
