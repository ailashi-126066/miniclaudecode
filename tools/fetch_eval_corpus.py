"""Fetch the pinned evaluation corpus into test-owned cache and verify manifest bytes."""

import hashlib
import io
import json
from pathlib import Path, PurePosixPath
from urllib.request import urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def main():
    manifest = json.loads((ROOT / "tests/evals/manifest.json").read_bytes())
    commit = manifest["commit"]
    url = f"https://codeload.github.com/Aider-AI/polyglot-benchmark/zip/{commit}"
    destination = ROOT / "tests/.runtime-tests/eval-cache/polyglot"
    if not destination.exists():
        with urlopen(url, timeout=60) as response:
            payload = response.read(100 * 1024 * 1024 + 1)
        if len(payload) > 100 * 1024 * 1024:
            raise ValueError("Pinned archive exceeds budget")
        with ZipFile(io.BytesIO(payload)) as archive:
            for member in archive.infolist():
                relative = PurePosixPath(member.filename)
                if relative.is_absolute() or ".." in relative.parts:
                    raise ValueError("Pinned archive contains an unsafe path")
                path = destination.joinpath(*relative.parts[1:])
                if member.is_dir():
                    path.mkdir(parents=True, exist_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(archive.read(member))
    count = 0
    line_endings = []
    for task in manifest["tasks"]:
        for name, expected in task["files_sha256"].items():
            path = destination / task["source_path"] / name
            body = path.read_bytes()
            original = hashlib.sha256(body).hexdigest()
            if original != expected:
                # The frozen Windows manifest used CRLF checkout bytes. Do not change its hashes.
                candidate = body.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
                if hashlib.sha256(candidate).hexdigest() != expected:
                    raise ValueError("Pinned task source hash differs: " + task["id"] + "/" + name)
                path.write_bytes(candidate)
                line_endings.append(
                    {
                        "task": task["id"],
                        "file": name,
                        "archive_sha256": original,
                        "manifest_sha256": expected,
                        "checkout_eol": "CRLF",
                    }
                )
            count += 1
        if task["smoke"] and not (destination / task["source_path"] / ".meta/example.py").is_file():
            raise ValueError("Pinned control solution is missing")
    evidence = {
        "cache": str(destination),
        "commit": commit,
        "verified_files": count,
        "line_ending_adaptations": line_endings,
    }
    (destination.parent / "source-verification.json").write_text(
        json.dumps(evidence, indent=2) + "\n"
    )
    print(
        json.dumps(
            {
                "cache": str(destination),
                "commit": commit,
                "verified_files": count,
                "line_ending_adaptations": len(line_endings),
            }
        )
    )


if __name__ == "__main__":
    main()
