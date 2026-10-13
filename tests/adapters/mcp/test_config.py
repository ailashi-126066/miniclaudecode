import json
from dataclasses import asdict
from pathlib import Path

import pytest

from pycode.adapters.mcp.config import parse_servers
from pycode.core.errors import DomainError
from pycode.core.mcp import loading_mode, mcp_spec

VECTORS = json.loads(
    (Path(__file__).parents[2] / "fixtures/mcp-baselines.json").read_text(encoding="utf-8")
)


def test_source_loading_threshold_vectors_and_native_metadata(tmp_path):
    for limit, chars, expected in VECTORS["threshold_vectors"]:
        assert loading_mode(limit, chars) == expected
    configured = parse_servers(
        {"s": {"command": "python", "env": {"TOKEN": "$ENV.TEST_TOKEN"}}}, tmp_path
    )[0]
    assert asdict(configured)["env"] == {"TOKEN": "$ENV.TEST_TOKEN"}
    declaration = {
        "name": "mcp__s__search",
        "server": "s",
        "description": "search",
        "input_schema": VECTORS["schema"]["inputSchema"],
        "trusted": True,
        "annotations": {"readOnlyHint": True},
        "schema_hash": "hash",
        "config_hash": "config",
    }
    assert mcp_spec(declaration).category == "read"
    assert mcp_spec({**declaration, "trusted": False}).category == "external"


@pytest.mark.parametrize(
    "configuration",
    [
        {"url": "file:///tmp/server"},
        {"command": "x", "env": {"TOKEN": "secret"}},
        {"command": "x", "timeout": True},
        {"command": "x", "args": "bad"},
        {"url": "https://user:secret@example.com/mcp"},
        {"command": "x", "trusted": "yes"},
    ],
)
def test_invalid_sources_and_literal_credentials_fail_at_configuration_boundary(
    tmp_path, configuration
):
    with pytest.raises(DomainError):
        parse_servers({"s": configuration}, tmp_path)
