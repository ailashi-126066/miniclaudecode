import asyncio

import pytest

from pycode.app.bootstrap import compose_application
from pycode.app.cli import main
from pycode.app.config import AppConfig
from pycode.kernel.domain.messages import Message
from tests.app.test_execution_cli import setup_cli
from tests.hooks.adapters.test_hooks import hook_file, open_session
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server


@pytest.mark.parametrize("action", ["result", "retry"])
def test_cli_reviews_cancelled_background_hook_and_retries_only_on_explicit_request(
    tmp_path, monkeypatch, capsys, action
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    asyncio.run(
        hook_file(
            config,
            [
                {
                    "id": "later",
                    "event": "post_receive",
                    "async": True,
                    "action": {
                        "type": "command",
                        "command": "printf old >> hook.txt; sleep 1",
                    },
                }
            ],
        )
    )
    with protocol_server([chat_completion()]) as (url, requests):
        common, execution = setup_cli(tmp_path, provider(url))

        async def run():
            session = await open_session(config, url, create=True)
            try:
                await session.run(Message("u1", "user", "task"))
                async with asyncio.timeout(5):
                    while not await asyncio.to_thread((tmp_path / "hook.txt").exists):  # noqa: ASYNC110
                        await asyncio.sleep(0.02)
            finally:
                await session.aclose()
            view = await compose_application(config).open_view("s1")
            try:
                entries = (await view.inspect())["snapshot"]["entries"]
                return next(
                    entry["operation_id"]
                    for entry in entries
                    if entry["type"] == "hook_call" and entry["data"]["hook"]["id"] == "later"
                )
            finally:
                await view.aclose()

        operation_id = asyncio.run(run())
        monkeypatch.delenv("PYCODE_RECOVERY_TEST_KEY")
        assert (
            main(
                [
                    *common,
                    "hooks",
                    *execution,
                    operation_id,
                    action,
                    "--text",
                    "Verified old output",
                ]
            )
            == 0
        )
        assert '"reviewed": true' in capsys.readouterr().out
        assert len(requests) == 1 and not (tmp_path / "late.txt").exists()
        assert (tmp_path / "hook.txt").read_text() == ("oldold" if action == "retry" else "old")
