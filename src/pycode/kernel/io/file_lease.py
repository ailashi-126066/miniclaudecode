"""Portable nonblocking single-writer lease; readers do not acquire this lease."""

import os
from pathlib import Path

if os.name == "nt":
    import msvcrt
else:
    import fcntl

from pycode.kernel.errors import StorageError


class FileLease:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._stream = None

    def acquire(self) -> bool:
        if self._stream is not None:
            return False
        self._path.parent.mkdir(parents=True, exist_ok=True)
        stream = self._path.open("a+b")
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            stream.close()
            raise StorageError("session_busy", "Another writer owns this session") from error
        self._stream = stream
        return True

    def close(self) -> None:
        if self._stream is None:
            return
        stream = self._stream
        self._stream = None
        stream.seek(0)
        try:
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        finally:
            stream.close()
