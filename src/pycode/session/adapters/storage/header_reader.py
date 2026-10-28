"""Synchronous bounded header reads for output ancestry checks in owned I/O threads."""

import re
from pathlib import Path

from pycode.kernel import SessionHeader, StorageError
from pycode.session.adapters.storage.session import read_header
from pycode.session.adapters.storage.session_codec import decode_header


class FileSessionHeaderReader:
    def __init__(self, sessions: Path):
        self.sessions = sessions

    def read(self, session_id: str) -> SessionHeader:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", session_id):
            raise StorageError("output_not_accessible", "Invalid output owner")
        path = self.sessions / session_id / "session.sqlite"
        if path.exists():
            return read_header(path)
        path = self.sessions.parent / "session-info" / (session_id + ".json")
        with path.open("rb") as source:
            raw = source.readline(262145)
        if len(raw) > 262144:
            raise StorageError("invalid_session_header", "Session header exceeds its size limit")
        return decode_header(raw)
