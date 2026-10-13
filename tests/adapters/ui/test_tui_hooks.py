from tests.adapters.ui.test_tui_http import settled
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_hooks import hook_file
from tests.workflows.test_revert_http import command_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_tui


async def test_tui_hook_rejects_tool_and_app_lifecycle_uses_same_event_source(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await hook_file(
        config,
        [
            {
                "id": "boot",
                "event": "startup",
                "action": {"type": "command", "command": "printf boot > boot.txt"},
            },
            {
                "id": "protect",
                "event": "pre_tool_use",
                "reject": True,
                "action": {"type": "prompt", "message": "Blocked by configured Hook"},
            },
            {
                "id": "close",
                "event": "shutdown",
                "action": {"type": "command", "command": "printf closed > closed.txt"},
            },
        ],
    )
    with protocol_server([command_reply("printf bad > parent.txt"), chat_completion()]) as (
        url,
        requests,
    ):
        app = build_tui(config, (provider(url),))
        async with app.run_test(size=(120, 50)):
            await app.ready.wait()
            assert (tmp_path / "boot.txt").exists()
            await app.send_text("command")
            await settled(app)
            assert app.last_error is None and not app.query("#perm-inline")
            assert not (tmp_path / "parent.txt").exists() and len(requests) == 2
            assert any(entry["type"] == "hook_result" for entry in app.state["snapshot"]["entries"])
        assert (tmp_path / "closed.txt").read_text() == "closed"
