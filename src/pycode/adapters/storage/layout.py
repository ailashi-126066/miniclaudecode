"""Platform-aware workspace identities and the reserved project storage layout."""

import hashlib
import os
from pathlib import Path


class StorageLayout:
    def __init__(self, workspace: Path, user_data: Path) -> None:
        normalized = os.path.normcase(str(workspace.resolve()))
        key = hashlib.sha256(normalized.encode()).hexdigest()[:24]
        self.project = user_data / "projects" / key
        self.sessions = self.project / "sessions"
        self.runtime_database = self.project / "runtime.sqlite"
