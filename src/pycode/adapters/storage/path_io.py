"""OS file access spelling after canonical scope checks; stored identities stay unchanged."""

import os
from pathlib import Path


def io_path(path):
    path = Path(path)
    if os.name != "nt":
        return path
    name = str(path.absolute())
    if name.startswith("\\\\?\\"):
        return path
    if name.startswith("\\\\"):
        return Path("\\\\?\\UNC\\" + name[2:])
    return Path("\\\\?\\" + name)
