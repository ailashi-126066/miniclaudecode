"""Editing and changing scope in the live memory panel requires explicit approval."""

from uuid import uuid4

from tests.adapters.ui.test_tui_http import settled
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from textual.widgets import Select, TextArea

from pycode.adapters.storage.memory import FileMemoryStore
from pycode.app.config import AppConfig
from pycode.app.container import build_tui
from pycode.core.memory import MemoryCandidate


async def test_tui_memory_review_edits_scope_and_never_calls_model_again(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([chat_completion()]) as (url, requests):
        app = build_tui(config, (provider(url),))
        async with app.run_test(size=(120, 65)) as pilot:
            await app.ready.wait()
            await app.send_text("Keep functions readable.")
            await settled(app)
            entry = next(
                item
                for item in app.state["snapshot"]["entries"]
                if item["message"] and item["message"]["role"] == "user"
            )
            store = FileMemoryStore(config.user_data / "memory", tmp_path / ".pycode" / "memory")
            candidate = MemoryCandidate(
                uuid4().hex,
                "add",
                "function-style",
                "user_preference",
                "user",
                "Keep functions readable.",
                ({"entry_id": entry["id"], "quote": "Keep functions readable."},),
                app.session_id,
                "source",
                "fixed",
                1,
            )
            await store.save_candidate(candidate)
            await app.command("/memory")
            await settled(app)
            assert app.query("#memory-panel")
            app.query_one("#memory-candidate", Select).value = candidate.candidate_id
            await pilot.pause()
            app.query_one("#memory-content", TextArea).load_text("Keep project functions readable.")
            app.query_one("#memory-scope", Select).value = "project"
            await pilot.pause()
            app.query_one("#memory-approve").scroll_visible(animate=False)
            await pilot.pause()
            button = app.query_one("#memory-approve")
            panel = app.query_one("#memory-panel")
            clicked = await pilot.click("#memory-approve", offset=(2, 1))
            assert clicked, (
                button.region,
                panel.region,
                panel.scroll_y,
                app.get_widget_at(button.region.x + 2, button.region.y + 1),
            )
            await settled(app)
            if app.last_error:
                raise app.last_error
            records = await store.list_records()
            assert len(records) == 1 and records[0].scope == "project"
            assert records[0].content == "Keep project functions readable."
            assert len(requests) == 1
