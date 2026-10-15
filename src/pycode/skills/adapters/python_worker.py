# Source: author-owned skills/directory.py, fixed SHA in docs/source-map.md.
# SHA-256: 2b04c95e8b75a23b8f65990b846e2ac76d08aaf5d18dadf55d76e0f7ff6b500f
# Adaptation: lazy execution in an owned process, exact bytes and both execute styles.
"""Trusted worker imports a resource only after the parent executor approved its call."""

import asyncio
import hashlib
import inspect
import json
import os
import stat
import sys
import types
from dataclasses import is_dataclass
from pathlib import Path

from pycode.kernel import json_dumps


def linked(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)


def implementation_files(root):
    candidates = [root / "tool.json"]
    references = root / "references"
    if linked(references):
        raise ValueError("Linked resource directory")
    for directory, names, files in os.walk(references, followlinks=False):
        names[:] = sorted(name for name in names if name != "__pycache__")
        if any(linked(Path(directory) / name) for name in names):
            raise ValueError("Linked resource directory")
        candidates.extend(Path(directory) / name for name in sorted(files) if name.endswith(".py"))
    hashes = {}
    if len(candidates) > 128:
        raise ValueError("Resource implementation count limit")
    for path in candidates:
        if linked(path) or not path.is_file() or path.stat().st_size > 1024 * 1024:
            raise ValueError("Invalid resource implementation")
        content = path.read_bytes()
        if len(content) > 1024 * 1024:
            raise ValueError("Resource implementation size limit")
        hashes[path.relative_to(root).as_posix()] = hashlib.sha256(content).hexdigest()
    return hashes


def execute(request):
    root = Path(request["skill_path"]).parent
    files = implementation_files(root)
    encoded = json_dumps(files, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
    if hashlib.sha256(encoded).hexdigest() != request["resource_hash"]:
        raise ValueError("Resource implementation changed before execution")
    path = root.joinpath(*request["relative_path"].split("/"))
    code = path.read_bytes()
    if hashlib.sha256(code).hexdigest() != request["script_hash"]:
        raise ValueError("Resource script changed before execution")
    module = types.ModuleType("pycode_skill_" + request["script_hash"])
    module.__file__ = str(path)
    sys.modules[module.__name__] = module
    sys.path.insert(0, str(root / "references"))
    exec(compile(code, str(path), "exec"), module.__dict__)
    function = getattr(module, "execute", None)
    if not callable(function):
        raise ValueError("Resource module must export callable execute")
    value = function(**request["arguments"])
    if inspect.isawaitable(value):
        value = asyncio.run(value)
    if is_dataclass(value) and hasattr(value, "is_error"):
        print(getattr(value, "text", getattr(value, "output", str(value))), flush=True)
        return 1 if value.is_error else 0
    print(
        json_dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else str(value),
        flush=True,
    )
    return 1 if isinstance(value, str) and value.startswith("Error:") else 0


def main():
    try:
        return execute(json.load(sys.stdin))
    except Exception as error:
        # A resource defect becomes a failed child, never a swallowed parent exception.
        print(f"Skill Python execution error: {type(error).__name__}: {error}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
