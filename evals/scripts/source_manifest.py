"""Deterministic source manifest recorded with quality evidence (from tools/record_verification)."""

import hashlib
import json


def manifest(root):
    records = []
    candidates = [root / "pyproject.toml", root / "requirements-dev.lock"]
    for path in sorted((root / "src").rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
            candidates.append(path)
    # The evaluation harness lives outside src/ but shapes every recorded result.
    for path in sorted((root / "evals").rglob("*.py")):
        relative = path.relative_to(root / "evals").parts
        if relative[0] not in {"tests", "data", "cache", "work", "results"}:
            candidates.append(path)
    candidates.extend(sorted((root / "docs" / "licenses").glob("*.txt")))
    for path in candidates:
        if not path.is_file():
            continue
        records.append(
            {
                "path": path.relative_to(root).as_posix(),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        )
    encoded = json.dumps(records, sort_keys=True, separators=(",", ":")).encode()
    return {"sha256": hashlib.sha256(encoded).hexdigest(), "files": records}
