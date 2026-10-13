"""Fetch immutable upstream archives without creating a Git checkout."""

import hashlib
import json
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINES = {
    "pi": ("earendil-works/pi", "42a3497d03ad17e308a2299fa824727894f2c0ec"),
    "opencode": ("anomalyco/opencode", "055d95bb7e278c94baf06235a52cac79dd13ba67"),
}


def main() -> None:
    cache = ROOT / ".source-cache"
    cache.mkdir(exist_ok=True)
    records = []
    for name, (repository, commit) in BASELINES.items():
        url = f"https://codeload.github.com/{repository}/zip/{commit}"
        archive = cache / f"{name}-{commit}.zip"
        if not archive.exists():
            temporary = archive.with_suffix(".partial")
            with urllib.request.urlopen(url, timeout=120) as response:
                with temporary.open("wb") as output:
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
            temporary.replace(archive)
        with zipfile.ZipFile(archive) as source:
            prefix = source.namelist()[0]
            destination = cache / name
            destination.mkdir(exist_ok=True)
            for member in source.infolist():
                relative = Path(member.filename.removeprefix(prefix))
                if member.is_dir():
                    continue
                target = (destination / relative).resolve()
                if not target.is_relative_to(destination.resolve()):
                    raise ValueError("Archive path escapes source cache")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read(member))
        with archive.open("rb") as stream:
            archive_digest = hashlib.file_digest(stream, "sha256").hexdigest()
        records.append(
            {
                "repository": repository,
                "commit": commit,
                "archive_url": url,
                "archive_sha256": archive_digest,
            }
        )
        print(f"Verified archive: {name} {commit}")
    output = ROOT / "docs" / "baselines" / "upstream.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
