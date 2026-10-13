from tests.adapters.ui.test_tui_http import settled
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.support.skill_resources import VECTORS, write_resource_skill
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_tui


async def test_resource_skill_command_shows_ordinary_approval_then_executes_owned_python(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    await write_resource_skill(config.user_data / "skills", kind="async")
    with protocol_server(
        [
            skill_reply("read_file", {"file_path": "/skills/user/resource/SKILL.md"}, "load"),
            skill_reply("resource_probe", VECTORS["argument"], "probe"),
            chat_completion(),
        ]
    ) as (url, requests):
        app = build_tui(config, (provider(url),))
        async with app.run_test(size=(120, 50)) as pilot:
            await app.ready.wait()
            await app.command("/skill:resource")
            await settled(app)
            assert app.last_error is None and not (tmp_path / "imported.txt").exists()
            assert app.query("#perm-inline") and not (tmp_path / "imported.txt").exists()
            await pilot.press("enter")
            await settled(app)
            assert app.last_error is None and len(requests) == 3
            assert (tmp_path / "imported.txt").exists() and app.state["pending_run_id"] is None
            stored = [
                entry["message"]["tool_result"]
                for entry in app.state["snapshot"]["entries"]
                if entry["message"] and entry["message"]["role"] == "tool"
            ]
            assert stored[-1]["text"].strip() == VECTORS["expected"] and stored[-1]["output_id"]
