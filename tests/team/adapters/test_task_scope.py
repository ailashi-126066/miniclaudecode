"""Task scope restricts both file writes and the command sandbox."""

from pycode.sandbox.adapters.seatbelt import build_profile
from pycode.tools.adapters.backend import PycodeBackend


def test_write_scope_does_not_restrict_reads_or_allow_unrelated_writes(tmp_path):
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    other = tmp_path / "other.py"
    other.write_text("original\n")
    backend = PycodeBackend(tmp_path, write_roots=(str(allowed),))
    assert backend.read("/other.py").error is None
    rejected = backend.edit("/other.py", "original", "changed")
    assert rejected.error and "outside" in rejected.error.lower()
    assert other.read_text() == "original\n"
    assert backend.write("/allowed/new.py", "ok\n").error is None


def test_command_profile_limits_workspace_writes_to_task_roots(tmp_path):
    scope = tmp_path / "src"
    scope.mkdir()
    profile = build_profile(tmp_path, False, home=tmp_path / "home", write_roots=(str(scope),))
    assert f'(allow file-write* (subpath "{scope}") (literal "{scope}"))' in profile
    assert f'(allow file-write* (subpath "{tmp_path}"))' not in profile
