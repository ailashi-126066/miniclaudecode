"""Fetch immutable upstream archives without creating a Git checkout."""

import argparse
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
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=ROOT / "tests/.runtime-tests/source-cache")
    parser.add_argument("--archives-only", action="store_true")
    args = parser.parse_args()
    cache = args.cache.resolve()
    if not cache.is_relative_to((ROOT / "tests/.runtime-tests").resolve()):
        raise ValueError("Source cache must stay under tests/.runtime-tests")
    cache.mkdir(parents=True, exist_ok=True)
    # Verify against the recorded baseline; fetching must never redefine it.
    records = json.loads((ROOT / "docs/baselines/upstream.json").read_bytes())
    if {(item["repository"], item["commit"]) for item in records} != set(BASELINES.values()):
        raise ValueError("Recorded upstream identities differ from the fixed baselines")
    for record in records:
        repository, commit = record["repository"], record["commit"]
        name = repository.split("/")[-1]
        url = f"https://codeload.github.com/{repository}/zip/{commit}"
        if record["archive_url"] != url:
            raise ValueError("Recorded URL differs from the fixed repository/commit")
        archive = cache / f"{name}-{commit}.zip"
        if not archive.exists():
            temporary = archive.with_suffix(".partial")
            with urllib.request.urlopen(url, timeout=120) as response:
                with temporary.open("wb") as output:
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
            with temporary.open("rb") as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if actual != record["archive_sha256"]:
                raise ValueError("Downloaded archive differs from the fixed hash: " + name)
            temporary.replace(archive)
        with archive.open("rb") as stream:
            archive_digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if archive_digest != record["archive_sha256"]:
            raise ValueError("Cached archive differs from the fixed hash: " + name)
        print(f"Verified archive: {name} {commit} {archive_digest}", flush=True)
        if args.archives_only:
            continue
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


if __name__ == "__main__":
    main()
