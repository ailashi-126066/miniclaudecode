"""Publish final evidence only after actual matching-source checks pass."""

import hashlib
import json
import platform
import sys
from datetime import datetime
from importlib.metadata import distributions
from pathlib import Path

from record_verification import junit_counts, manifest


def read_json(path):
    return json.loads(path.read_bytes())


def main():
    root = Path(__file__).resolve().parents[1]
    directory = root / "docs/verification"
    source = manifest(root)
    checks = read_json(directory / "delivery-checks.json")
    if checks["status"] != "passed" or checks["source_sha256"] != source["sha256"]:
        raise RuntimeError("The installed build has not passed against this source")
    junit = directory / "acceptance-tests.xml"
    counts = junit_counts(junit)
    if counts["failures"] or counts["errors"]:
        raise RuntimeError("The complete regression still has failures")
    evaluations = []
    for path in sorted((root / "tests/evals/results/acceptance").glob("*.json")):
        result = read_json(path)
        evaluations.append(
            {
                "task": result["task"],
                "outcome": result["outcome"],
                "independent_exit_code": result["independent_tests"]["exit_code"],
                "model": result["model"],
                "source_commit": result["source_commit"],
                "request_count": result["agent"]["usage"]["session"]["request_count"],
                "artifact": path.relative_to(root).as_posix(),
            }
        )
    if len(evaluations) != 3:
        raise RuntimeError("The actual three-task evaluation has not finished")
    remote = read_json(directory / "live-deepseek-acceptance.json")
    if remote["status"] != "passed":
        raise RuntimeError("The configured real provider acceptance has not passed")
    versions = {}
    for item in distributions():
        versions[item.metadata["Name"]] = item.version
    now = datetime.now().astimezone()
    report = {
        "date": now.date().isoformat(),
        "updated_at": now.isoformat(),
        "timezone": "Asia/Shanghai",
        "platform": platform.platform(),
        "python": sys.version,
        "status": "completed_local",
        "source_sha256": source["sha256"],
        "source_manifest": source,
        "versions": versions,
        "lock_sha256": hashlib.sha256((root / "requirements-dev.lock").read_bytes()).hexdigest(),
        "dependency_resolution": {
            "report": "tests/dependency-artifacts/resolution-final.json",
            "command": (
                ".venv/Scripts/python.exe -m pip install --dry-run --ignore-installed "
                "--no-cache-dir --no-build-isolation "
                '--report tests/dependency-artifacts/resolution-final.json ".[dev,team]"'
            ),
        },
        "full_regression": {
            "command": (
                ".venv/Scripts/python.exe -X utf8 -m pytest -q --basetemp tests/.t/acceptance "
                "--junitxml docs/verification/acceptance-tests.xml"
            ),
            "report": "docs/verification/acceptance-tests.xml",
            **counts,
        },
        "delivery_checks": checks,
        "source_baseline": {
            "source_reference": "docs/source-map.md",
            "unchanged_files": 229,
            "fixed_archives": 2,
            "log": "docs/verification/final-sources.log",
        },
        "dependency_tree": "docs/verification/dependency-tree-final.json",
        "dependency_note": (
            "No direct standalone MCP SDK, extra MCP adapter or SQLite graph checkpointer. "
            "langchain[mcp] necessarily supplies fastmcp/mcp transitively."
        ),
        "real_model_loop": {
            "command": (
                ".venv/Scripts/python.exe -X utf8 tools/verify_live_provider.py "
                "--provider deepseek "
                "--model deepseek-flash --base-url https://api.deepseek.com --thinking enabled "
                "--max-output 256 --output docs/verification/live-deepseek-acceptance.json"
            ),
            "report": "docs/verification/live-deepseek-acceptance.json",
            "status": remote["status"],
            "requests": len(remote["requests"]),
        },
        "real_evaluation": {
            "command": (
                ".venv/Scripts/python.exe -X utf8 -m pycode --memory-mode off eval "
                "--providers tests/evals/providers-final.json --work tests/evals/work/acceptance "
                "--results tests/evals/results/acceptance"
            ),
            "kind": "actual_remote_model_coding_smoke",
            "results": evaluations,
        },
        "protocol_control": "docs/verification/evaluation-control.json",
        "github_skill_install": "docs/verification/skill-online-final.json",
        "artifacts": [],
        "unverified": [
            {
                "item": "OpenAI remote provider",
                "reason": "OPENAI_API_KEY not configured",
                "report": "docs/verification/live-openai-final.json",
            },
            {
                "item": "Anthropic remote provider",
                "reason": "ANTHROPIC_API_KEY not configured",
                "report": "docs/verification/live-anthropic-final.json",
            },
            {
                "item": "Remote MCP service",
                "reason": (
                    "No remote endpoint/credentials configured; "
                    "real local stdio and HTTP protocol tests passed"
                ),
            },
            {
                "item": "Langfuse remote ingestion",
                "reason": (
                    "Public/secret credentials not configured; real local HTTP privacy test passed"
                ),
            },
            {
                "item": (
                    "macOS Seatbelt physical isolation, terminal keyboard/layout "
                    "and target-platform 12-task full evaluation"
                ),
                "reason": "Current host is Windows",
                "commands": "docs/verification/platform-boundaries.md",
            },
            {
                "item": "Windows physical file symlink snapshot",
                "reason": (
                    "WinError 1314: file symlink privilege absent; existing test skip retained"
                ),
            },
        ],
        "history_integration": "Not performed, as explicitly excluded by the user",
    }
    for path in sorted((root / "dist").glob("pycode-*")):
        report["artifacts"].append(
            {
                "path": path.relative_to(root).as_posix(),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "bytes": path.stat().st_size,
            }
        )
    destination = directory / "final-verification.json"
    destination.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    migration = read_json(directory / "deepagents-migration.json")
    migration.update(
        status="completed_local",
        current_source_sha256=source["sha256"],
        updated_at=now.isoformat(),
        final_report=destination.relative_to(root).as_posix(),
        remaining_implementation=[],
        unverified=report["unverified"],
        full_regression=report["full_regression"],
    )
    (directory / "deepagents-migration.json").write_text(
        json.dumps(migration, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        json.dumps({"status": report["status"], "tests": counts, "source_sha256": source["sha256"]})
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
