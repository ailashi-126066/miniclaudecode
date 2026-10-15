import asyncio
import json
import sys
from pathlib import Path

import pytest

from pycode.adapters.sandbox.seatbelt import SeatbeltSandbox, UnavailableSandbox, build_profile
from pycode.adapters.tools.bash import Bash
from pycode.core.errors import ToolError
from pycode.core.operations import operation_spec_hash
from pycode.core.sandbox import SandboxSettings


def test_profile_preserves_source_read_network_and_quoted_path_rules(tmp_path):
    root = str(tmp_path / 'quoted"path')
    profile = build_profile(root, False)
    assert '(allow file-read* (subpath "/"))' in profile
    assert json.dumps(str(Path(root).resolve()), ensure_ascii=False) in profile
    assert "(deny network*)" in profile and ".git" in profile and ".pycode" in profile
    assert "(deny file-write-unlink" in profile
    assert "(allow network*)" in build_profile(root, True)
    assert '(allow file-write-data (literal "/dev/null"))' in profile


@pytest.mark.skipif(sys.platform != "darwin", reason="Physical Seatbelt requires macOS")
async def test_physical_seatbelt_allows_standard_pytest_logging_without_outside_file_writes(
    tmp_path,
):
    sandbox = SeatbeltSandbox()
    assert (await sandbox.status(SandboxSettings("on"))).available
    test = tmp_path / "test_probe.py"
    test.write_text("def test_standard_logging():\n    assert 2 + 2 == 4\n")
    argv = await sandbox.launch(
        (sys.executable, "-m", "pytest", "-q", str(test), "--rootdir", str(tmp_path)),
        str(tmp_path),
        SandboxSettings("on"),
    )
    process = await asyncio.create_subprocess_exec(
        *argv,
        cwd=str(tmp_path),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    async with asyncio.timeout(15):
        stdout, stderr = await process.communicate()
    assert process.returncode == 0, (stdout + stderr).decode(errors="replace")
    assert b"1 passed" in stdout


async def test_unavailable_backend_preserves_ordinary_launch_and_never_claims_auto_approval():
    backend = UnavailableSandbox()
    for mode in ("off", "on", "on-auto"):
        settings = SandboxSettings(mode)
        status = await backend.status(settings)
        assert not status.available and not status.enabled and not status.auto_approve
        assert await backend.launch(("bash", "-c", "echo test"), "/project", settings) == (
            "bash",
            "-c",
            "echo test",
        )


async def test_missing_seatbelt_does_not_execute_unsandboxed_when_explicitly_enabled(tmp_path):
    backend = SeatbeltSandbox(str(tmp_path / "missing"))
    assert not (await backend.status(SandboxSettings("on-auto"))).auto_approve
    with pytest.raises(ToolError) as error:
        await backend.launch(("bash", "-c", "touch file"), str(tmp_path), SandboxSettings("on"))
    assert error.value.code == "sandbox_unavailable"


def test_sandbox_configuration_is_part_of_operation_identity():
    plain = Bash("bash", None)
    enclosed = Bash("bash", None, settings=SandboxSettings("on"))
    automatic = Bash("bash", None, settings=SandboxSettings("on-auto"))
    assert len({operation_spec_hash(tool.spec) for tool in (plain, enclosed, automatic)}) == 3
