from pycode.app.composition import build_tui
from pycode.app.config import AppConfig
from tests.session.adapters.test_revert_http import command_reply
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.ui.adapters.test_tui_http import settled


async def test_standard_80_by_24_terminal_keeps_prompt_and_focused_approval_visible(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([command_reply("printf ok > file.txt"), chat_completion()]) as (
        url,
        requests,
    ):
        app = build_tui(AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), (provider(url),))
        async with app.run_test(size=(80, 24)) as pilot:
            await app.ready.wait()
            await app.send_text("write")
            await settled(app)
            await pilot.pause()
            assert app.focused.id == "perm-inline"
            assert app.focused.region.height > 0
            assert app.screen.region.contains_region(app.focused.region)
            await pilot.press("enter")
            await settled(app)
            assert (tmp_path / "file.txt").read_text(encoding="utf-8") == "ok"
            assert len(requests) == 2
