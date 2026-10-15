"""Run one project check and append immutable evidence to 测试数据.md."""

import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from zoneinfo import ZoneInfo

from record_verification import junit_counts, manifest


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--id", required=True)
    parser.add_argument("--purpose", required=True)
    parser.add_argument("--supersedes", default="无")
    parser.add_argument("--junit")
    parser.add_argument("--artifact", action="append", default=[])
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("a command is required")
    root = Path(__file__).resolve().parents[1]

    def now():
        return datetime.now(ZoneInfo("Asia/Shanghai"))

    started = now()
    run_id = started.strftime("%Y%m%dT%H%M%S%f") + "-" + args.id
    directory = root / "tests" / ".runtime-tests" / "reports" / run_id
    directory.mkdir(parents=True)
    source = manifest(root)
    tests = []
    excluded = {".runtime-tests", "__pycache__", "cache", "work", "results", "dependency-artifacts"}
    for directory_name, directories, filenames in os.walk(root / "tests"):
        directories[:] = [name for name in directories if name not in excluded]
        for filename in sorted(filenames):
            if filename.endswith(".py"):
                path = Path(directory_name) / filename
                tests.append({"path": str(path.relative_to(root)), "sha256": digest(path)})
    tests.sort(key=lambda item: item["path"])
    source["tests"] = tests
    source_path = directory / "source.json"
    source_path.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n")
    versions = {}
    for name in (
        "deepagents",
        "langchain",
        "langgraph",
        "langchain-core",
        "langmem",
        "pytest",
        "ruff",
        "editables",
    ):
        try:
            versions[name] = version(name)
        except PackageNotFoundError:
            versions[name] = "not installed"
    environment = dict(os.environ)
    environment.update(PYTHONDONTWRITEBYTECODE="1", OMP_NUM_THREADS="1", OPENBLAS_NUM_THREADS="1")
    temp = root / "tests" / ".runtime-tests" / "tmp"
    temp.mkdir(exist_ok=True)
    environment["TMPDIR"] = str(temp)
    log_path = directory / "output.log"
    print(f"run_id={run_id}", flush=True)
    exit_code = 130
    status = "interrupted"
    try:
        with log_path.open("w") as log:
            result = subprocess.run(
                command,
                cwd=root,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
            )
        exit_code = result.returncode
        status = "passed" if exit_code == 0 else "failed"
    finally:
        ended = now()
        counts = (
            junit_counts(root / args.junit)
            if args.junit and (root / args.junit).is_file()
            else None
        )
        failure_summary = "无"
        skipped = []
        if counts is not None:
            failed = []
            for case in ET.parse(root / args.junit).iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failed.append(case.get("name"))
                skip = case.find("skipped")
                if skip is not None:
                    skipped.append({"test": case.get("name"), "reason": skip.get("message")})
            if failed:
                failure_summary = failed
        elif exit_code != 0:
            failure_summary = "Command failed; diagnostic output is in output.log"
        artifacts = {
            str(source_path.relative_to(root)): digest(source_path),
            str(log_path.relative_to(root)): digest(log_path),
        }
        if counts is not None:
            artifacts[args.junit] = digest(root / args.junit)
        for filename in args.artifact:
            path = (root / filename).resolve()
            if not path.is_relative_to(root):
                raise ValueError("Check artifacts must remain in the project")
            artifacts[str(path.relative_to(root))] = digest(path)
        evidence = {
            "test_run_id": run_id,
            "status": status,
            "supersedes": args.supersedes,
            "test_id": args.id,
            "purpose": args.purpose,
            "source_sha256": source["sha256"],
            "plan_sha256": digest(root / "第二个计划.md"),
            "manifest_sha256": digest(root / "tests/evals/manifest.json"),
            "lock_sha256": digest(root / "requirements-dev.lock"),
            "platform": platform.platform(),
            "python": sys.version,
            "versions": versions,
            "command": command,
            "configuration": (
                "Serial; isolated project temp; one numerical worker; "
                "no real memory/model credentials"
            ),
            "model": "N/A (deterministic checks)",
            "started": started.isoformat(),
            "ended": ended.isoformat(),
            "seconds": (ended - started).total_seconds(),
            "exit_code": exit_code,
            "counts": counts,
            "failure_summary": failure_summary,
            "skip_reasons": skipped,
            "unverified_reason": "无" if exit_code == 0 else "命令未通过，见失败摘要及原始日志",
            "test_manifest_sha256": hashlib.sha256(
                json.dumps(tests, sort_keys=True).encode()
            ).hexdigest(),
            "check_tool_sha256": digest(Path(__file__)),
            "artifacts": artifacts,
        }
        evidence_path = directory / "result.json"
        evidence_path.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        with (root / "测试数据.md").open("a", encoding="utf-8") as index:
            index.write("\n### `" + run_id + "`\n\n")
            for key, value in evidence.items():
                index.write(f"- {key}：{json.dumps(value, ensure_ascii=False)}\n")
            index.write(
                f"- 完整记录：`{evidence_path.relative_to(root)}`；"
                f"SHA-256：`{digest(evidence_path)}`\n"
            )
        print(
            json.dumps(
                {"run_id": run_id, "status": status, "exit_code": exit_code, "counts": counts},
                ensure_ascii=False,
            )
        )
        if log_path.is_file():
            preview = log_path.read_text(errors="replace").splitlines()[-35:]
            print(
                "\n".join(
                    line[:1200] + (" [preview clipped]" if len(line) > 1200 else "")
                    for line in preview
                )
            )
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
