"""Record source identities before porting any local modules."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def file_record(path: Path, root: Path) -> dict:
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {
        "path": path.relative_to(root).as_posix(),
        "size": path.stat().st_size,
        "sha256": digest,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--local-source", type=Path, required=True)
    args = parser.parse_args()
    local = args.local_source.resolve()
    records = []
    for path in sorted(local.rglob("*")):
        relative = path.relative_to(local)
        if any(part.startswith(".") or part == "__pycache__" for part in relative.parts):
            continue
        if path.is_file() and path.suffix in {".py", ".toml", ".md", ".tcss", ".json"}:
            records.append(file_record(path, local))
    table = ["| 来源路径 | 字节数 | SHA-256 |", "| --- | ---: | --- |"]
    for record in records:
        table.append(f"| `{record['path']}` | {record['size']} | `{record['sha256']}` |")
    source_map = ROOT / "docs" / "source-map.md"
    marker = "<!-- LOCAL_SOURCE_MANIFEST -->"
    document = source_map.read_text(encoding="utf-8").split(marker)[0]
    source_map.write_text(document + marker + "\n\n" + "\n".join(table) + "\n", encoding="utf-8")
    upstream_records = []
    licenses = ROOT / "docs" / "licenses"
    for name in ("pi", "opencode"):
        source = ROOT / ".source-cache" / name
        shutil.copyfile(source / "LICENSE", licenses / f"{name}-MIT.txt")
        for path in sorted(source.rglob("*")):
            if path.is_file() and path.suffix in {".ts", ".json"}:
                record = file_record(path, source)
                record["source"] = name
                upstream_records.append(record)
    destination = ROOT / "docs" / "baselines" / "upstream-files.json"
    destination.write_text(json.dumps(upstream_records, indent=2) + "\n", encoding="utf-8")
    print(f"Recorded {len(records)} local files and {len(upstream_records)} upstream files")


if __name__ == "__main__":
    main()
