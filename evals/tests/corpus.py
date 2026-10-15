"""Locate the gitignored pinned Exercism corpus used by the local control tests."""

import os
from pathlib import Path

ROOT = Path(__file__).parents[2]
# Checkouts that predate the evals/ package keep the corpus under tests/.runtime-tests.
LEGACY = ROOT / "tests/.runtime-tests/eval-cache/polyglot"


def polyglot_cache():
    configured = os.environ.get("PYCODE_EVAL_CACHE")
    if configured:
        return Path(configured)
    current = ROOT / "evals/cache/polyglot"
    return current if current.exists() or not LEGACY.exists() else LEGACY
