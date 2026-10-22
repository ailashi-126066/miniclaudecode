"""The live panel reads JSON memory; edit, delete and undo need no approval or model call."""

import hashlib

import pytest

from pycode.app.bootstrap import compose_application
from pycode.app.configuration.settings import AppConfig
from pycode.memory.adapters.storage.json_memory import JsonMemoryStore, MemoryChangeLog
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.ui.adapters.test_tui_http import settled


async def test_tui_memory_panel_edit_delete_and_undo(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    with protocol_server([chat_completion()]) as (url, requests):
        app = compose_application(config, (provider(url),)).tui()
        async with app.run_test(size=(120, 65)):
            await app.ready.wait()
            await app.send_text("Keep functions readable.")
            await settled(app)
            user_id = hashlib.sha256(str(config.user_data.resolve()).encode()).hexdigest()[:24]
            namespace = ("memories", "user", user_id)
            store = JsonMemoryStore({namespace: config.user_data / "memory"})
            await store.aput(namespace, "style", {"content": "Keep functions readable."})
            await app.command("/memory")
            await settled(app)
            assert app.query("#memory-panel")
            await app.command("/memory edit style Keep functions readable and explicit.")
            await settled(app)
            if app.last_error:
                raise app.last_error
            records = (await app.session.memory_list())["records"]
            assert len(records) == 1 and records[0]["memory_id"] == "style"
            assert records[0]["scope"] == "user"
            assert records[0]["content"] == "Keep functions readable and explicit."
            await app.command("/memory delete style")
            await settled(app)
            if app.last_error:
                raise app.last_error
            assert not (await app.session.memory_list())["records"]

            # Simulate one automatic consolidation in the change log, then undo it.
            view = store.recording()
            await view.aput(namespace, "auto", {"content": "Prefers pytest."})
            await MemoryChangeLog(config.user_data / "memory" / "changes.jsonl").append(
                {
                    "id": "c1",
                    "at": 0,
                    "origin": "consolidate",
                    "changes": [{**change, "scope": "user"} for change in view.changes],
                }
            )
            assert len((await app.session.memory_list())["records"]) == 1
            await app.command("/memory undo")
            await settled(app)
            if app.last_error:
                raise app.last_error
            assert not (await app.session.memory_list())["records"]
            assert len(requests) == 1


async def test_tui_memory_edit_and_delete_can_disambiguate_scope(tmp_path, monkeypatch):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    user, project = compose_application(config)._modules.memory.namespaces
    store = JsonMemoryStore(
        {user: config.user_data / "memory", project: tmp_path / ".pycode" / "memory"}
    )
    await store.aput(user, "same", {"content": "user preference"})
    await store.aput(project, "same", {"content": "project preference"})
    with protocol_server([]) as (url, requests):
        app = compose_application(config, (provider(url),)).tui()
        async with app.run_test(size=(120, 65)):
            await app.ready.wait()
            await app.command("/memory edit same changed project preference --scope project")
            await settled(app)
            if app.last_error:
                raise app.last_error
            assert (await store.aget(project, "same")).value["content"][
                "content"
            ] == "changed project preference"
            assert (await store.aget(user, "same")).value["content"]["content"] == "user preference"
            await app.command("/memory delete same --scope project")
            await settled(app)
            if app.last_error:
                raise app.last_error
            assert await store.aget(project, "same") is None
            assert await store.aget(user, "same") is not None
        assert not requests


# This suite tests execution with tool definitions already disclosed.
pytestmark = pytest.mark.usefixtures("disclosed_tools")
