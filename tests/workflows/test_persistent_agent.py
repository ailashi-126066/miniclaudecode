from tests.support.http_provider import chat_completion, protocol_server

from pycode.app.config import AppConfig
from pycode.app.container import build_session_repository, open_agent_session
from pycode.core.messages import Message
from pycode.core.models import ProviderConfig


async def test_application_service_uses_durable_projection_and_common_real_framework(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_APPLICATION_API_KEY", "fake-application-key")
    with protocol_server([chat_completion(), chat_completion()]) as (url, received):
        provider = ProviderConfig(
            "configured",
            "openai",
            "wire-model",
            url + "/v1",
            "PYCODE_APPLICATION_API_KEY",
            32000,
            4096,
            openai_api="chat_completions",
        )
        config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
        session = await open_agent_session(
            config, (provider,), "s1", create=True, refresh_models=False
        )
        assert (await session.run(Message("u1", "user", "first task"))).outcome == "completed"
        assert (await session.run(Message("u1", "user", "first task"))).outcome == "completed"
        await session.aclose()
        restored = await open_agent_session(config, (provider,), "s1", refresh_models=False)
        assert (await restored.run(Message("u2", "user", "second task"))).outcome == "completed"
        await restored.aclose()
        assert len(received) == 2
        assert any(
            message.get("content") == "first task" for message in received[1]["body"]["messages"]
        )
        store = await build_session_repository(config).open("s1")
        snapshot = await store.snapshot()
        assert len([entry for entry in snapshot.entries if entry.type == "message"]) == 4
        assert len([entry for entry in snapshot.entries if entry.type == "model_change"]) == 1
        await store.aclose()
