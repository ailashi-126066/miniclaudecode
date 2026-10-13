"""Actual JSON declarations and exact source registration reach the composition root."""

import json

import pytest
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server

from pycode.app.config import AppConfig, load_team_agents
from pycode.app.container import open_agent_session
from pycode.core.errors import DomainError
from pycode.core.messages import Message


@pytest.mark.parametrize("value", ["true", 1, None])
def test_team_json_keeps_strict_boolean_validation(tmp_path, value):
    config = AppConfig(tmp_path, tmp_path / "data")
    config.user_data.mkdir()
    (config.user_data / "team.json").write_text(
        json.dumps(
            [
                {
                    "name": "worker",
                    "description": "Worker",
                    "graph_id": "worker",
                    "url": "http://127.0.0.1:9",
                    "modifies_workspace": value,
                }
            ]
        ),
        encoding="utf-8",
    )
    with pytest.raises(DomainError) as error:
        load_team_agents(config)
    assert error.value.code == "invalid_team_config"


async def test_nonempty_team_json_allows_an_ordinary_main_agent_run(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    config.user_data.mkdir()
    (config.user_data / "team.json").write_text(
        json.dumps(
            [
                {
                    "name": "worker",
                    "description": "Worker",
                    "graph_id": "worker",
                    "url": "http://127.0.0.1:9",
                    "modifies_workspace": True,
                }
            ]
        ),
        encoding="utf-8",
    )
    assert load_team_agents(config)[0].modifies_workspace is True
    with protocol_server([chat_completion()]) as (url, requests):
        session = await open_agent_session(
            config, (provider(url),), "s", create=True, refresh_models=False
        )
        try:
            result = await session.run(Message("u", "user", "Inspect the project"))
            assert result.outcome == "completed" and len(requests) == 1
        finally:
            await session.aclose()
