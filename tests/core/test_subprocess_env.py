import pytest

from pycode.core.subprocess_env import subprocess_environment


def test_allowlisted_names_and_locale_prefix_are_kept():
    source = {
        "PATH": "/usr/bin",
        "HOME": "/home/user",
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "TMPDIR": "/tmp",
        "VIRTUAL_ENV": "/venv",
        "SystemRoot": "C:\\Windows",
        "EDITOR": "vim",
        "PYTHONPATH": "/elsewhere",
    }
    assert subprocess_environment(source) == {
        "PATH": "/usr/bin",
        "HOME": "/home/user",
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "TMPDIR": "/tmp",
        "VIRTUAL_ENV": "/venv",
        "SystemRoot": "C:\\Windows",
    }


@pytest.mark.parametrize(
    "name",
    [
        "FAKE_API_KEY",
        "OPENAI_API_KEY",
        "github_token",
        "CLIENT_SECRET",
        "LANGFUSE_SECRET_KEY",
        "DB_PASSWORD",
        "AWS_ACCESS_KEY_ID",
        "AWS_PROFILE",
        "LC_SECRET",
        "LC_TOKEN",
    ],
)
def test_credential_shaped_names_are_dropped(name):
    assert subprocess_environment({name: "value", "PATH": "/bin"}) == {"PATH": "/bin"}
