"""Pure historical contributors remain usable without runtime module activation."""

from types import SimpleNamespace

from pycode.mcp.history import MCPProjection
from pycode.skills.history import SkillsProjection


def test_empty_history_removes_only_owned_checkpoint():
    snapshot = SimpleNamespace(index={})
    skills = SkillsProjection().contribute(snapshot, (), (), {"skills": ["stale"], "mcp": {}})
    mcp = MCPProjection().contribute(snapshot, (), (), {"skills": ["stale"], "mcp": {}})
    assert skills.checkpoint_removals == frozenset({"skills"})
    assert mcp.checkpoint_removals == frozenset({"mcp"})
    assert not skills.entries and not mcp.entries


def test_mcp_projection_uses_persisted_catalog_only():
    catalog = {"hash": "hash", "tools": [], "text": "catalog"}
    entry = SimpleNamespace(id="catalog-entry", type="mcp_catalog", data=catalog)
    projected = MCPProjection().contribute(SimpleNamespace(), (entry,), (), {})
    assert projected.entries[0].entry_id == "catalog-entry"
    assert projected.entries[0].messages[0].text == "catalog"
    assert projected.checkpoint_updates["mcp"]["hash"] == "hash"
