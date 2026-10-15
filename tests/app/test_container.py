from pycode.app.composition import build_container
from pycode.app.config import AppConfig


def test_explicit_container_has_no_filesystem_write(tmp_path):
    config = AppConfig(workspace=tmp_path, user_data=tmp_path / "user-data", memory_mode="off")
    container = build_container(config)
    status = container.application.status()
    assert status.name == "pycode"
    assert status.workspace.exists
    assert not status.workspace.snapshot_available
    assert list(tmp_path.iterdir()) == []
