"""The live panel reads migrated JSON; explicit edits require no memory approval or model call."""

from uuid import uuid4

from tests.adapters.ui.test_tui_http import settled
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.support.legacy_memory_store import FileMemoryStore
from tests.support.legacy_memory_types import MemoryRecord

from pycode.app.config import AppConfig
from pycode.app.container import build_tui
from pycode.core.memory import content_hash


async def test_tui_json_memory_inspection_and_explicit_edit_without_candidate_approval(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([chat_completion()]) as (url, requests):
        app = build_tui(config, (provider(url),))
        async with app.run_test(size=(120, 65)):
            await app.ready.wait()
            await app.send_text("Keep functions readable.")
            await settled(app)
            entry = next(
                item
                for item in app.state["snapshot"]["entries"]
                if item["message"] and item["message"]["role"] == "user"
            )
            legacy = FileMemoryStore(config.user_data / "memory", tmp_path / ".pycode" / "memory")
            key = uuid4().hex
            await legacy.write(
                MemoryRecord(
                    key,
                    "function-style",
                    "user_preference",
                    "user",
                    "Keep functions readable.",
                    1,
                    1,
                    (
                        {
                            "entry_id": entry["id"],
                            "quote": "Keep functions readable.",
                            "source_session_id": app.session_id,
                        },
                    ),
                    content_hash("Keep functions readable."),
                )
            )
            await app.command("/memory")
            await settled(app)
            assert app.query("#memory-panel")
            assert not app.query("#memory-candidate") and not app.query("#memory-approve")
            assert (config.user_data / "memory/memory.json").is_file()
            await app.command(f"/memory edit {key} Keep functions readable and explicit.")
            await settled(app)
            if app.last_error:
                raise app.last_error
            records = (await app.session.memory_list())["records"]
            assert len(records) == 1 and records[0]["memory_id"] == key
            assert records[0]["scope"] == "user"
            assert records[0]["content"] == "Keep functions readable and explicit."
            await app.command(f"/memory move {key} project")
            await settled(app)
            if app.last_error:
                raise app.last_error
            records = (await app.session.memory_list())["records"]
            assert len(records) == 1 and records[0]["scope"] == "project"
            assert records[0]["memory_id"] == key
            await app.command(f"/memory move {key} user --from-scope project")
            await settled(app)
            if app.last_error:
                raise app.last_error
            assert (await app.session.memory_list())["records"][0]["scope"] == "user"
            await app.command(f"/memory remove {key}")
            await settled(app)
            assert not (await app.session.memory_list())["records"]
            assert len(requests) == 1
