"""Presentation shares immutable entry views; a cached read does not clone every message."""

import pytest
from tests.support.requests import TestTiming

from pycode.adapters.storage.session import JsonlSessionStore
from pycode.app.config import AppConfig
from pycode.app.container import build_session_view
from pycode.core.messages import Message
from pycode.core.session import SessionEntry, SessionHeader


async def test_5000_entry_presentation_reuses_messages_and_appends_one_view(tmp_path):
    store = JsonlSessionStore(
        tmp_path / "session", SessionHeader("s", str(tmp_path), "t"), TestTiming()
    )
    entries = []
    parent = None
    for number in range(5000):
        identifier = str(number)
        entries.append(
            SessionEntry(
                identifier,
                parent,
                "message",
                "t",
                message=Message(
                    "m" + identifier, "user" if number % 2 == 0 else "assistant", "body " * 200
                ),
            )
        )
        parent = identifier
    await store.import_path(tuple(entries), parent)
    view = build_session_view(
        AppConfig(tmp_path, tmp_path / "data", memory_mode="off"), store, session_id="s"
    )
    try:
        first = await view.inspect(presentation=True)
        second = await view.inspect(presentation=True)
        assert first["history"][0]["message"] is second["history"][0]["message"]
        assert first["snapshot"]["entries"][0] is second["snapshot"]["entries"][0]
        with pytest.raises(TypeError):
            first["history"][0]["message"]["text"] = "changed"
        await store.append_message(Message("new", "user", "new"))
        added = await view.inspect(presentation=True)
        assert added["history"][0]["message"] is first["history"][0]["message"]
        assert added["history"][-1]["message"]["text"] == "new"
    finally:
        await view.aclose()
        await store.aclose()
