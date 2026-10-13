"""Source resource-tool vectors, written as real files without importing them."""

import asyncio
import json
from pathlib import Path

VECTORS = json.loads(
    (Path(__file__).parents[1] / "fixtures/skill-tools-baselines.json").read_text(encoding="utf-8")
)


async def write_resource_skill(
    root, *, kind="sync", name="resource", tool="resource_probe", code=None
):
    directory = root / name

    def write():
        references = directory / "references"
        references.mkdir(parents=True, exist_ok=True)
        (directory / "SKILL.md").write_text(
            f"---\nname: {name}\ndescription: Resource task\nallowedTools: [{tool}]\n"
            "---\nResource instructions $ARGUMENTS\n",
            encoding="utf-8",
        )
        declaration = {**VECTORS["declaration"], "name": tool}
        (directory / "tool.json").write_text(json.dumps([declaration]), encoding="utf-8")
        implementation = code or (
            "from pathlib import Path\nPath('imported.txt').write_text('imported')\n"
            + VECTORS[kind + "_implementation"]
        )
        (references / (tool + ".py")).write_text(implementation, encoding="utf-8")

    await asyncio.to_thread(write)
    return directory
