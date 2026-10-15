"""Freeze twelve Python tasks, three smoke tasks, source hashes and attribution."""

import hashlib
import json
from pathlib import Path

TASKS = (
    "affine-cipher",
    "beer-song",
    "grade-school",
    "phone-number",
    "pig-latin",
    "proverb",
    "transpose",
    "variable-length-quantity",
    "wordy",
    "list-ops",
    "bowling",
    "book-store",
)
COMMIT = "7e0611e77b54e2dea774cdc0aa00cf9f7ed6144f"


def main():
    root = Path(__file__).resolve().parents[2]
    cache = root / "evals/cache/polyglot"
    tasks = []
    for name in TASKS:
        relative = "python/exercises/practice/" + name
        directory = cache / relative
        config = json.loads((directory / ".meta/config.json").read_bytes())
        solution = config["files"]["solution"]
        tests = config["files"]["test"]
        files = [*solution, *tests, ".docs/instructions.md", ".meta/config.json"]
        if (directory / ".docs/instructions.append.md").exists():
            files.append(".docs/instructions.append.md")
        hashes = {}
        for filename in files:
            hashes[filename] = hashlib.sha256((directory / filename).read_bytes()).hexdigest()
        tasks.append(
            {
                "id": name,
                "source_path": relative,
                "solution_files": solution,
                "test_files": tests,
                "files_sha256": hashes,
                "test_command": ["-m", "pytest", "-q", *tests],
                "timeout_seconds": 60,
                "smoke": name in TASKS[:3],
            }
        )
    output = root / "evals/data/manifest.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "repository": "https://github.com/Aider-AI/polyglot-benchmark.git",
                "commit": COMMIT,
                "language": "Python",
                "tasks": tasks,
                "attribution": "Exercism Python track MIT License, copyright 2021 Exercism.",
                "license_source": "https://github.com/exercism/python/blob/main/LICENSE",
                "upstream_attribution": (
                    "https://github.com/Aider-AI/polyglot-benchmark/blob/" + COMMIT + "/README.md"
                ),
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"tasks": len(tasks), "smoke": 3, "commit": COMMIT}))


if __name__ == "__main__":
    main()
