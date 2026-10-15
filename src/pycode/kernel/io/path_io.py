"""Shared file I/O after caller scope checks: OS paths, hashes and owned atomic writes."""

import asyncio
import hashlib
import os
from pathlib import Path, PurePosixPath
from uuid import uuid4

from pycode.kernel.errors import StorageError


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


def safe_relative(base, name):
    relative = PurePosixPath(name)
    if not name or relative.is_absolute() or ".." in relative.parts or "\\" in name:
        raise StorageError("memory_path_invalid", "Memory file name is not a safe relative path")
    path = base.joinpath(*relative.parts)
    if not path.resolve().is_relative_to(base.resolve()):
        raise StorageError("memory_path_invalid", "Memory file path escapes its root")
    for ancestor in [path, *path.parents]:
        if ancestor == base:
            break
        if ancestor.is_symlink():
            raise StorageError("memory_path_invalid", "Memory file path contains a symlink")
    return path


def file_sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def atomic_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode if path.exists() else None
    temporary = path.with_name(f".{uuid4().hex}.pycode-write.partial")
    try:
        with temporary.open("xb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        if mode is not None:
            temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


async def owned_io(function, *arguments):
    task = asyncio.create_task(asyncio.to_thread(function, *arguments))
    try:
        return await asyncio.shield(task)
    except asyncio.CancelledError:
        await task
        raise
