"""Check built wheel/sdist for package identity, required notices and leaked caches."""

import argparse
import json
import tarfile
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    args = parser.parse_args()
    wheel = next(args.dist.glob("pycode-*.whl"))
    sdist = next(args.dist.glob("pycode-*.tar.gz"))
    with zipfile.ZipFile(wheel) as archive:
        wheel_files = archive.namelist()
        metadata = archive.read("pycode-0.1.0.dist-info/METADATA").decode()
        entrypoints = archive.read("pycode-0.1.0.dist-info/entry_points.txt").decode()
    with tarfile.open(sdist) as archive:
        sdist_files = archive.getnames()
    forbidden = (
        "/.venv/",
        "/__pycache__/",
        "/.runtime-tests/",
        "/.fix-20261013/",
        "/audit-20261013/",
        "/evals/cache/",
        "/evals/work/",
        "/evals/results/",
        "/.env",
        "/.git/",
    )
    for name in [*wheel_files, *sdist_files]:
        for fragment in forbidden:
            if fragment in "/" + name and not name.endswith("/.env.example"):
                raise ValueError("Distribution includes private/generated content: " + name)
    assert "pycode = pycode.app.cli:main" in entrypoints and "\npy =" not in entrypoints
    for package, version in (
        ("deepagents", "0.7.23"),
        ("langchain", "1.4.4"),
        ("langgraph", "1.2.14"),
    ):
        assert package in metadata and "==" + version in metadata
    assert "langgraph-checkpoint-sqlite" not in metadata
    assert "pycode/licenses/pi-MIT.txt" in wheel_files
    assert "pycode/licenses/opencode-MIT.txt" in wheel_files
    assert "pycode/licenses/exercism-MIT.txt" in wheel_files
    print(
        json.dumps(
            {
                "wheel": wheel.name,
                "sdist": sdist.name,
                "wheel_files": len(wheel_files),
                "sdist_files": len(sdist_files),
                "status": "passed",
            }
        )
    )


if __name__ == "__main__":
    main()
