"""Record actual final checks, build artifacts and a fresh wheel installation."""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4

from record_verification import manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path, default=Path("docs/verification"))
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    evidence = (root / args.evidence_dir).resolve()
    distribution = (root / args.dist).resolve()
    if not evidence.is_relative_to(root) or not distribution.is_relative_to(root):
        raise RuntimeError("Verification artifacts must remain inside the project")
    evidence.mkdir(parents=True, exist_ok=True)
    report_path = evidence / "delivery-checks.json"
    source = manifest(root)
    report = {"source_sha256": source["sha256"], "status": "running", "checks": []}

    def check(name, argv, *, cwd=root, environment=None):
        started = time.monotonic()
        result = subprocess.run(
            argv, cwd=cwd, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
        )
        log = evidence / ("delivery-" + name + ".log")
        log.write_bytes(result.stdout)
        report["checks"].append(
            {
                "name": name,
                "argv": [str(item) for item in argv],
                "cwd": str(cwd),
                "exit_code": result.returncode,
                "elapsed_seconds": time.monotonic() - started,
                "log": log.relative_to(root).as_posix(),
            }
        )
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(name + ": " + str(result.returncode), flush=True)
        if result.returncode:
            raise RuntimeError("Delivery check failed: " + name)

    python = sys.executable
    check("lint", [python, "-m", "ruff", "check", "src", "tests", "tools"])
    check("format", [python, "-m", "ruff", "format", "--check", "src", "tests", "tools"])
    check("architecture", [python, "tools/check_architecture.py"])
    check("pip-check", [python, "-m", "pip", "check"])
    check("build", [python, "-m", "build", "--no-isolation", "--outdir", str(distribution)])
    check("distribution", [python, "tools/verify_distribution.py", "--dist", str(distribution)])
    install_root = (root / "tests/.runtime-tests" / ("install-" + uuid4().hex[:8])).resolve()
    if not install_root.is_relative_to((root / "tests").resolve()) or install_root.exists():
        raise RuntimeError("Use a new test-owned clean installation directory")
    check("venv", [python, "-m", "venv", str(install_root)])
    installed_python = install_root / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    executable = install_root / ("Scripts/pycode.exe" if os.name == "nt" else "bin/pycode")
    constraints = evidence / "constraints-delivery.txt"
    constraints.write_text(
        (root / "requirements-dev.lock").read_text().replace("langchain[mcp]", "langchain")
    )
    wheel = distribution / "pycode-0.1.0-py3-none-any.whl"
    check(
        "install",
        [
            str(installed_python),
            "-m",
            "pip",
            "install",
            "--no-cache-dir",
            "--only-binary=:all:",
            "-c",
            str(constraints),
            str(wheel),
        ],
    )
    clean_environment = dict(os.environ)
    clean_environment.pop("PYTHONPATH", None)
    check(
        "installed-pip-check",
        [str(installed_python), "-m", "pip", "check"],
        cwd=install_root,
        environment=clean_environment,
    )
    check("help", [str(executable), "--help"], cwd=install_root, environment=clean_environment)
    check(
        "module-help",
        [str(installed_python), "-m", "pycode", "--help"],
        cwd=install_root,
        environment=clean_environment,
    )
    probe = """import json, pathlib, sys
from importlib.metadata import requires, version
import pycode
assert pathlib.Path(pycode.__file__).resolve().is_relative_to(pathlib.Path(sys.prefix).resolve())
direct = requires('pycode')
for requirement in direct:
    name = requirement.split(';')[0].split('[')[0].split('==')[0].split('>=')[0].strip()
    assert name not in {'mcp', 'langchain-mcp-adapters', 'langgraph-checkpoint-sqlite', 'aiosqlite'}
for name, expected in {'deepagents': '0.7.23', 'langchain': '1.4.4', 'langgraph': '1.2.14'}.items():
    assert version(name) == expected
print(json.dumps({'module': pycode.__file__, 'direct_dependencies': direct, 'status': 'passed'}))
"""
    check(
        "installed-dependencies",
        [str(installed_python), "-c", probe],
        cwd=install_root,
        environment=clean_environment,
    )
    if manifest(root)["sha256"] != source["sha256"]:
        raise RuntimeError("Source changed during delivery checks")
    report["status"] = "passed"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
