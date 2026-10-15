"""Check every pinned coding baseline under the actual OS test sandbox, without a model."""

import argparse
import asyncio
import json
import platform
import sys
from pathlib import Path

from pycode.adapters.evaluation.workspaces import EvaluationWorkspaces
from pycode.app.container import build_sandbox
from pycode.core.sandbox import SandboxSettings


async def verify(args, root):
    spaces = EvaluationWorkspaces(
        root / "tests/evals/manifest.json",
        args.cache,
        args.work,
        args.output.parent / "logs",
        sandbox=build_sandbox(),
        sandbox_settings=SandboxSettings("on" if sys.platform == "darwin" else "off"),
    )
    report = {
        "model_requests": 0,
        "platform": platform.platform(),
        "tasks": [],
        "status": "running",
    }
    for task in spaces.tasks:
        workspace = await spaces.prepare(task)
        try:
            result = await spaces.test(task, workspace, Path(sys.executable))
            checked = (
                result["exit_code"] in (0, 1) and result["junit"] and result["junit"]["tests"] > 0
            )
            report["tasks"].append(
                {"task": task.task_id, "ready": bool(checked), "baseline": result}
            )
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            print(task.task_id + ": " + ("ready" if checked else "unavailable"), flush=True)
        finally:
            await spaces.cleanup(workspace)
    report["status"] = "passed" if all(item["ready"] for item in report["tasks"]) else "failed"
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return 0 if report["status"] == "passed" else 1


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--cache", type=Path, default=root / "tests/.runtime-tests/eval-cache/polyglot"
    )
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for path in (args.cache, args.work, args.output):
        if not path.resolve().is_relative_to((root / "tests/.runtime-tests").resolve()):
            raise ValueError("Baseline verification artifacts must stay under tests/.runtime-tests")
    if args.output.exists():
        raise ValueError("Use a fresh report path; retain previous evidence")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    return asyncio.run(verify(args, root))


if __name__ == "__main__":
    raise SystemExit(main())
