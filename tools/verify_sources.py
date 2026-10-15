"""Verify the local reference remains equal to the recorded immutable baseline."""

import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--local-source", type=Path, required=True)
    parser.add_argument(
        "--archive-dir", type=Path, default=ROOT / "tests/.runtime-tests/source-cache"
    )
    args = parser.parse_args()
    source_map = (ROOT / "docs" / "source-map.md").read_text(encoding="utf-8")
    pattern = re.compile(r"^\| `([^`]+)` \| (\d+) \| `([a-f0-9]{64})` \|$", re.MULTILINE)
    errors = []
    count = 0
    for path, size, expected in pattern.findall(source_map):
        original = args.local_source / path
        if not original.is_file():
            errors.append(f"Missing local source: {path}")
            continue
        with original.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected or original.stat().st_size != int(size):
            errors.append(f"Local source changed: {path}")
        count += 1
    upstream = ROOT / "docs" / "baselines" / "upstream.json"
    for record in json.loads(upstream.read_text(encoding="utf-8")):
        name = record["repository"].split("/")[-1]
        archive = args.archive_dir / f"{name}-{record['commit']}.zip"
        if not archive.is_file():
            errors.append(f"Missing upstream archive: {name}")
            continue
        with archive.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != record["archive_sha256"]:
            errors.append(f"Upstream archive changed: {name}")
    if count == 0:
        errors.append("Local baseline manifest is empty")
    for error in errors:
        print(error)
    if errors:
        return 1
    print(f"Verified {count} unchanged local source files and 2 fixed upstream archives")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
