from pathlib import Path

import pytest

from mewcode.skills.install import SkillSource, _has_skill_manifest, install_skill


def test_installer_requires_skill_md(tmp_path):
    (tmp_path / "skill.yaml").write_text("name: audit\ndescription: Audit\n")
    (tmp_path / "prompt.md").write_text("Old prompt")
    assert not _has_skill_manifest(tmp_path)
    (tmp_path / "SKILL.md").write_text("---\nname: audit\ndescription: Audit\n---\nbody")
    assert _has_skill_manifest(tmp_path)


@pytest.mark.asyncio
async def test_rejected_yaml_install_preserves_existing_skill(tmp_path, monkeypatch):
    installed = tmp_path / "audit"
    installed.mkdir()
    manifest = installed / "SKILL.md"
    manifest.write_text("Existing skill")

    async def download(client, source, subpath, target, report, depth):
        (target / "skill.yaml").write_text("name: audit\ndescription: Audit\n")
        (target / "prompt.md").write_text("Old prompt")

    monkeypatch.setattr("mewcode.skills.install._walk_and_download", download)
    source = SkillSource("owner", "repo", "main", "skills/audit", "audit", "https://example.invalid")
    with pytest.raises(RuntimeError, match="missing SKILL.md"):
        await install_skill(source, install_root=tmp_path)
    assert manifest.read_text() == "Existing skill"
    assert not list(tmp_path.glob(".install-*"))
