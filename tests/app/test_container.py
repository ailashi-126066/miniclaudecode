import json

import pytest

from pycode.app.config import AppConfig, load_team_concurrency
from pycode.app.container import build_container, build_evaluation_runner
from pycode.core.errors import StorageError


def test_explicit_container_has_no_filesystem_write(tmp_path):
    config = AppConfig(workspace=tmp_path, user_data=tmp_path / "user-data", memory_mode="off")
    container = build_container(config)
    status = container.application.status()
    assert status.name == "pycode"
    assert status.workspace.exists
    assert not status.workspace.snapshot_available
    assert list(tmp_path.iterdir()) == []


def test_evaluation_rejects_work_directories_outside_its_owned_root(tmp_path):
    config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
    root = tmp_path / "tests" / "evals"
    with pytest.raises(StorageError, match="tests/evals"):
        build_evaluation_runner(
            config,
            (),
            root / "manifest.json",
            root / "cache",
            tmp_path / "unrelated",
            root / "results",
        )


def test_team_concurrency_uses_only_trusted_project_override(tmp_path):
    user = tmp_path / "data"
    user.mkdir()
    project = tmp_path / ".pycode"
    project.mkdir()
    (user / "team-settings.json").write_text(json.dumps({"concurrency": 2}))
    (project / "team-settings.json").write_text(json.dumps({"concurrency": 6}))
    assert load_team_concurrency(AppConfig(tmp_path, user)) == 2
    assert load_team_concurrency(AppConfig(tmp_path, user, project_trusted=True)) == 6
