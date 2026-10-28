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
        "deepagents": "0.7.32",
        "langchain": "1.4.7",
        "langgraph": "1.2.18",
    }.items():
        if versions.get(name) != required:
            raise ValueError("Harness dependency changed: " + name)
    if {"langchain-mcp-adapters"} & set(versions):
        raise ValueError("Unexpected adapter or main-chat checkpointer in dependency tree")
    lines = [
        "# Python 3.12 dev/eval dependencies resolved from pyproject.toml.",
        "# Regenerate with a clean pip --dry-run --ignore-installed --report resolution.",
    ]
    windows = {
        name: versions.pop(name, default)
        for name, default in {"pywin32-ctypes": "0.2.3", "pywin32": "312"}.items()
    }
    for name in sorted(versions):
        requirement = "langchain[mcp]" if name == "langchain" else name
        lines.append(requirement + "==" + versions[name])
    lines.extend(f'{name}=={version}; sys_platform == "win32"' for name, version in windows.items())
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"locked_distributions": len(versions), "harness": "deepagents==0.7.32"}))


if __name__ == "__main__":
    main()
