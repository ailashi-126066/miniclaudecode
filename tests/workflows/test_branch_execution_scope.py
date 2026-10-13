from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server

from pycode.app.config import AppConfig
from pycode.app.container import build_session_repository, open_agent_session
from pycode.core.messages import Message


async def test_public_branch_to_pre_settlement_history_can_start_new_run(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([chat_completion(), chat_completion()]) as (url, requests):
        configured = provider(url)
        session = await open_agent_session(
            config, (configured,), "s1", create=True, refresh_models=False
        )
        await session.run(Message("u1", "user", "initial"))
        reader = await build_session_repository(config).open("s1")
        snapshot = await reader.snapshot()
        before_settlement = next(
            entry.id
            for entry in reversed(snapshot.entries)
            if entry.type == "message" and entry.message.role == "assistant"
        )
        await reader.aclose()
        await session.branch(before_settlement)
        assert (
            await session.run(Message("u2", "user", "continue old branch"))
        ).outcome == "completed"
        assert len(requests) == 2
        await session.aclose()


async def test_conversation_fork_does_not_inherit_source_execution_owner(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([chat_completion(), chat_completion()]) as (url, requests):
        configured = provider(url)
        parent = await open_agent_session(
            config, (configured,), "s1", create=True, refresh_models=False
        )
        await parent.run(Message("u1", "user", "parent task"))
        await parent.aclose()
        repository = build_session_repository(config)
        source = await repository.open("s1")
        original = await source.snapshot()
        leaf = next(
            entry.id
            for entry in reversed(original.entries)
            if entry.type == "message" and entry.message.role == "assistant"
        )
        await source.aclose()
        copied = await repository.fork("s1", leaf, "forked")
        await copied.aclose()
        fork = await open_agent_session(config, (configured,), "forked", refresh_models=False)
        assert (await fork.run(Message("fork-u1", "user", "continue fork"))).outcome == "completed"
        await fork.aclose()
        source = await repository.open("s1")
        assert await source.snapshot() == original
        await source.aclose()
        assert len(requests) == 2
