import asyncio
import threading
from dataclasses import replace

from tests.adapters.ui.test_tui_http import settled
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_revert_http import command_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_tui


async def test_actual_tui_cancelled_sdk_request_is_collected_and_new_input_uses_new_task(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    first = chat_completion()
    started, release = threading.Event(), threading.Event()
    first.started, first.release = started, release
    with protocol_server([first, chat_completion()]) as (url, requests):
        app = build_tui(AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), (provider(url),))
        async with app.run_test(size=(120, 45)) as pilot:
            try:
                await app.ready.wait()
                await app.send_text("task")
                assert await asyncio.to_thread(started.wait, 5)
                await pilot.press("ctrl+c")
                await settled(app)
                assert not app.busy and app.state["pending_run_id"] is None
                ended = [
                    entry
                    for entry in app.state["snapshot"]["entries"]
                    if entry["data"].get("kind") == "run_end"
                ]
                assert ended[-1]["data"]["outcome"] == "interrupted"
                run_id = ended[-1]["run_id"]
                release.set()
                await app.send_text("Continue")
                await settled(app)
                assert app.state["pending_run_id"] is None and len(requests) == 2
                assert (await app.session.usage())["run_id"] != run_id
            finally:
                release.set()


async def test_tui_thinking_and_usage_are_projected_from_real_provider_messages(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    with protocol_server([chat_completion(deepseek=True)]) as (url, requests):
        selected = replace(provider(url), implementation="deepseek")
        app = build_tui(AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), (selected,))
        async with app.run_test(size=(120, 45)):
            await app.ready.wait()
            await app.send_text("think")
            await settled(app)
            assert len(requests) == 1
            rendered = "\n".join(
                str(widget.render()) for widget in app.query("#conversation Static")
            )
            assert "Thinking from provider" in rendered and "cache hit 12" in rendered
            assert "reasoning 3" in rendered


async def test_cancel_after_confirmed_tool_result_does_not_redisplay_consumed_approval(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    delayed = chat_completion()
    started, release = threading.Event(), threading.Event()
    delayed.started, delayed.release = started, release
    with protocol_server([command_reply("printf x >> count.txt"), delayed, chat_completion()]) as (
        url,
        requests,
    ):
        app = build_tui(AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), (provider(url),))
        async with app.run_test(size=(120, 45)) as pilot:
            try:
                await app.ready.wait()
                await app.send_text("run once")
                await settled(app)
                await pilot.press("enter")
                assert await asyncio.to_thread(started.wait, 5)
                await pilot.press("ctrl+c")
                await settled(app)
                assert (tmp_path / "count.txt").read_text(encoding="utf-8") == "x"
                assert not app.query("#perm-inline")
                release.set()
                await app.command("/resume")
                await settled(app)
                assert len(requests) == 2
                await app.send_text("Continue without repeating the command")
                await settled(app)
                assert (tmp_path / "count.txt").read_text(encoding="utf-8") == "x"
                assert app.state["pending_run_id"] is None and len(requests) == 3
            finally:
                release.set()


async def test_tui_steer_is_durable_and_delivered_after_current_model_settles(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    delayed = chat_completion()
    started, release = threading.Event(), threading.Event()
    delayed.started, delayed.release = started, release
    with protocol_server([delayed, chat_completion()]) as (url, requests):
        app = build_tui(AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), (provider(url),))
        async with app.run_test(size=(120, 45)):
            try:
                await app.ready.wait()
                await app.send_text("initial")
                assert await asyncio.to_thread(started.wait, 5)
                await app.send_text("queued correction")
                assert len(requests) == 1
                release.set()
                await settled(app)
                assert len(requests) == 2
                assert any(
                    message.get("content") == "queued correction"
                    for message in requests[-1]["body"]["messages"]
                )
                assert any(
                    item["text"] == "queued correction" for item in app.state["context"]["messages"]
                )
            finally:
                release.set()


async def test_closing_tui_collects_active_request_and_reopening_keeps_recovery_state(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    delayed = chat_completion()
    started, release = threading.Event(), threading.Event()
    delayed.started, delayed.release = started, release
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([delayed, chat_completion()]) as (url, requests):
        app = build_tui(config, (provider(url),))
        try:
            async with app.run_test(size=(120, 45)):
                await app.ready.wait()
                await app.send_text("initial")
                assert await asyncio.to_thread(started.wait, 5)
                original = app.session_id
            assert not app.busy
            release.set()
            reopened = build_tui(config, (provider(url),), session_id=original)
            async with reopened.run_test(size=(120, 45)):
                await reopened.ready.wait()
                assert reopened.state["pending_run_id"] is None
                await reopened.send_text("Continue")
                await settled(reopened)
                assert reopened.state["pending_run_id"] is None and len(requests) == 2
        finally:
            release.set()
