import asyncio
import json
from pathlib import Path

import pytest

from pycode.kernel.domain.errors import ToolError
from pycode.skills.adapters.catalog import FileSkillCatalog
from pycode.skills.adapters.parser import parse_skill
from pycode.tools.adapters.schema import JsonSchemaValidator

VECTORS = json.loads(
    (Path(__file__).parents[2] / "fixtures/skill-baselines.json").read_text(encoding="utf-8")
)


def test_source_parser_vectors():
    skill = parse_skill(VECTORS["valid"], "/skills/test/SKILL.md", "user")
    assert skill.entry.name == "test-skill" and skill.entry.allowed_tools == ("Bash", "Read")
    for raw in VECTORS["invalid"]:
        with pytest.raises(ToolError):
            parse_skill(raw, "/skills/test/SKILL.md", "user")


async def test_recursive_sources_override_in_fixed_order_without_importing_scripts(tmp_path):
    roots = []
    for source in VECTORS["precedence"]:
        root = tmp_path / source
        nested = root / "nested/task"
        nested.mkdir(parents=True)
        (nested / "SKILL.md").write_text(
            VECTORS["valid"].replace("A test skill", source), encoding="utf-8"
        )
        (nested / "danger.py").write_text(
            "raise RuntimeError('must never import')", encoding="utf-8"
        )
        roots.append((source, root))
    catalog = FileSkillCatalog(tuple(roots), validator=JsonSchemaValidator())
    index = await catalog.scan()
    assert len(index.entries) == 1 and index.entries[0].source == "project"
    assert (await catalog.read(index.entries[0])).entry.description == "project"
    await asyncio.to_thread(
        Path(index.entries[0].path).write_text,
        VECTORS["valid"].replace("A test skill", "changed"),
        encoding="utf-8",
    )
    with pytest.raises(ToolError) as error:
        await catalog.read(index.entries[0])
    assert error.value.code == "skill_changed"
