"""Record real JUnit results and a deterministic source manifest during implementation."""

import argparse
import hashlib
import json
import platform
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from importlib.metadata import version
from pathlib import Path


def manifest(root):
    records = []
    candidates = [root / "pyproject.toml", root / "requirements-dev.lock"]
    for path in sorted((root / "src").rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
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


def junit_counts(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for suite in suites:
        for field in counts:
            counts[field] += int(suite.attrib.get(field, 0))
    counts["passed"] = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
    return counts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--junit", action="append", default=[])
    parser.add_argument("--command", action="append", default=[])
    args = parser.parse_args()
    if len(args.junit) != len(args.command):
        parser.error("Each JUnit report requires its actual command")
    root = Path(__file__).resolve().parents[1]
    directory = root / "docs" / "verification"
    report_path = directory / "deepagents-migration.json"
    report = json.loads(report_path.read_bytes())
    source = manifest(root)
    report["current_source_sha256"] = source["sha256"]
    report["updated_at"] = datetime.now().astimezone().isoformat()
    report["platform"] = platform.platform()
    report["python"] = sys.version
    report["status"] = "in_progress"
    for package in ("deepagents", "langchain", "langgraph", "starlette", "uvicorn"):
        report["versions"][package] = version(package)
    checks = report.setdefault("implementation_checks", [])
    for filename, command in zip(args.junit, args.command, strict=True):
        path = root / filename
        counts = junit_counts(path)
        checks.append(
            {
                "command": command,
                "report": filename,
                **counts,
                "status": "passed" if not counts["failures"] and not counts["errors"] else "failed",
                "report_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "source_note": "Implementation active; final checks require one source manifest.",
            }
        )
    (directory / "migration-source-manifest.json").write_text(
        json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"source_sha256": source["sha256"], "added_reports": len(args.junit)}))


if __name__ == "__main__":
    main()
