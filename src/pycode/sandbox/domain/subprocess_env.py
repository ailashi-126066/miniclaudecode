"""Child processes receive an allowlisted environment without inherited credentials."""

import re
from collections.abc import Mapping

# Names a shell, git, Python or a virtual environment needs to behave normally.
ALLOWED_NAMES = frozenset(
    {
        "PATH",
        "HOME",
        "USER",
        "LOGNAME",
        "SHELL",
        "LANG",
        "LANGUAGE",
        "TERM",
        "TMPDIR",
        "TZ",
        "VIRTUAL_ENV",
        # Windows processes cannot start Python, sockets or the shell without these.
        "SYSTEMROOT",
        "WINDIR",
        "COMSPEC",
        "PATHEXT",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "USERNAME",
        "HOMEDRIVE",
        "HOMEPATH",
        "SYSTEMDRIVE",
        "APPDATA",
        "LOCALAPPDATA",
        "PROGRAMDATA",
        "PROGRAMFILES",
        "PROGRAMFILES(X86)",
    }
)
ALLOWED_PREFIXES = ("LC_",)
CREDENTIAL = re.compile(
    r".*_API_KEY|.*_TOKEN|.*SECRET.*|.*PASSWORD.*|.*PASSWD.*|.*_CREDENTIALS?|AWS_.*",
    re.IGNORECASE,
)


def credential_name(name: str) -> bool:
    return CREDENTIAL.fullmatch(name) is not None


def allowed_name(name: str) -> bool:
    upper = name.upper()
    if credential_name(upper):
        return False
    return upper in ALLOWED_NAMES or upper.startswith(ALLOWED_PREFIXES)


def subprocess_environment(source: Mapping[str, str]) -> dict[str, str]:
    """Keep allowlisted names; credential-shaped names are dropped even if allowlisted."""
    return {name: value for name, value in source.items() if allowed_name(name)}
