"""CLI relocation and edit --scope use the actual JSON service without a provider."""

import asyncio
import json

from tests.services.test_json_memory import service_at
from tests.services.test_memory_moves import seed

from pycode.app.cli import main


def test_cli_move_and_edit_scope_preserve_id_with_no_provider(tmp_path, capsys):
    async def setup():
        history, service, entry = await service_at(tmp_path)
        await seed(service, entry)
        await service.aclose()
        await history.aclose()

    asyncio.run(setup())
    options = ["--workspace", str(tmp_path), "--data-dir", str(tmp_path / "data"), "--session", "s"]
    assert main([*options, "memory", "move", "stable", "project"]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["memory_id"] == "stable" and result["scope"] == "project"
    assert (
        main(
            [
                *options,
                "memory",
                "edit",
                "stable",
                "Global explanation preference.",
                "--scope",
                "user",
            ]
        )
        == 0
    )
    result = json.loads(capsys.readouterr().out)
    assert result["memory_id"] == "stable"
    assert main([*options, "memory", "list"]) == 0
    result = json.loads(capsys.readouterr().out)
    assert len(result["records"]) == 1
    assert result["records"][0]["scope"] == "user"
    assert result["records"][0]["content"] == "Global explanation preference."
