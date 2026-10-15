"""Turn a clean pip resolution report into exact development requirements."""

import argparse
import json
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, default=Path("requirements-dev.lock"))
    args = parser.parse_args()
    report = json.loads(args.report.read_bytes())
    versions = {}
    for item in report["install"]:
        metadata = item["metadata"]
        name = re.sub(r"[-_.]+", "-", metadata["name"]).lower()
        if name != "pycode":
            versions[name] = metadata["version"]
    for name, required in {
        "deepagents": "0.7.23",
        "langchain": "1.4.4",
        "langgraph": "1.2.14",
    }.items():
        if versions.get(name) != required:
            raise ValueError("Harness dependency changed: " + name)
    if {"langchain-mcp-adapters", "langgraph-checkpoint-sqlite", "aiosqlite"} & set(versions):
        raise ValueError("Unexpected adapter or main-chat checkpointer in dependency tree")
    lines = [
        "# Windows Python 3.12; clean --ignore-installed resolution of "
        "pyproject.toml [dev,team,eval].",
        "# See archive/verification/post-review-fixes.json for the resolution evidence.",
    ]
    for name in sorted(versions):
        requirement = "langchain[mcp]" if name == "langchain" else name
        lines.append(requirement + "==" + versions[name])
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"locked_distributions": len(versions), "harness": "deepagents==0.7.23"}))


if __name__ == "__main__":
    main()
