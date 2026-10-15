"""Configuration-only opens and native call scopes do not bypass lifecycle policy."""

from contextlib import asynccontextmanager
from types import SimpleNamespace

import pytest

from pycode.kernel import StorageError
from pycode.mcp.adapters.native import MCPNativeBinding
from pycode.mcp.module import MCPModule, MCPSettings
from pycode.mcp.services.answers import MCPQuestionExtension


@pytest.mark.asyncio
async def test_config_only_open_validates_hash_without_connections(tmp_path, monkeypatch):
    server = SimpleNamespace(name="fixture")
    monkeypatch.setattr("pycode.mcp.module.load_servers", lambda roots, workspace: (server,))
    monkeypatch.setattr("pycode.mcp.module.plain_data", lambda value: {"name": value.name})

    def forbidden(*args, **kwargs):
        raise AssertionError("config-only must not construct connections")

    monkeypatch.setattr("pycode.mcp.module.MCPAdapterConnections", forbidden)
    module = MCPModule(schema=None)
    settings = MCPSettings(tmp_path, tmp_path)
    session = await module.open_session(settings, {}, connect=False)
    assert session.config_hash
    assert session.connections is None
    assert session.prepare_run(None, 2000) is None
    await session.aclose()
    pending = SimpleNamespace(data={"mcp_config_hash": "changed"})
    with pytest.raises(StorageError, match="configuration changed"):
        await module.open_session(settings, {}, pending=pending, connect=False)


@pytest.mark.asyncio
async def test_native_lookup_has_no_effect_until_authorized_scope():
    events = []

    @asynccontextmanager
    async def scope(name):
        events.append(("enter", name))
        yield
        events.append(("exit", name))

    binding = MCPNativeBinding(
        SimpleNamespace(framework_tool=lambda name: "native", tool_call=scope)
    )
    assert binding.framework_tool("tool") == "native"
    assert events == []
    async with binding.call_scope("tool"):
        assert events == [("enter", "tool")]
    assert events[-1] == ("exit", "tool")


@pytest.mark.asyncio
async def test_elicitation_is_persisted_once_and_validated_by_owner():
    entries = []

    class History:
        async def snapshot(self):
            return SimpleNamespace(entries=entries)

        async def append_entry(self, draft):
            entries.append(SimpleNamespace(type=draft.type, data=draft.data))

    question = {
        "type": "mcp_elicitation",
        "interrupt_id": "q",
        "requests": [{"key": "name", "mode": "form", "requested_schema": {"type": "object"}}],
    }
    checked = []
    validator = SimpleNamespace(
        check_schema=lambda schema: None, validate=lambda schema, content: checked.append(content)
    )
    extension = MCPQuestionExtension()
    answer = {"responses": {"name": {"action": "accept", "content": {"value": "yes"}}}}
    assert extension.validate(question, answer, validator)
    assert checked == [{"value": "yes"}]
    assert not extension.validate({"kind": "permission"}, {}, validator)
    first = await extension.prepare(question, History(), "run")
    await extension.prepare(question, History(), "run")
    assert first["kind"] == "mcp_elicitation"
    assert len(entries) == 1
