# Phase 0 verification — 2026-10-10

Environment: Windows, project virtual environment Python 3.12.10.
Development directory contains no `.git`; diagnostics correctly report Git
snapshots unavailable. No reference project was modified.

Verified results:

- `python -m pytest -q`: 27 passed, including reverse dependency violations,
  relative imports, framework boundaries, brand residue, composition root,
  module entry point and read-only workspace inspection.
- `ruff check .`: passed after import/format fixes.
- `python tools/check_architecture.py`: architecture and branding checks passed.
- `python tools/verify_sources.py --local-source <reference-directory>`:
  229 local source files unchanged, both fixed upstream archive hashes matched.
- `python -m build --no-isolation`: wheel and sdist built successfully.
- Editable package installation succeeded; `py --version` and
  `python -m pycode doctor --json` succeeded.
- Wheel inspected: registered `py = pycode.app.cli:main`, all three source license
  and author-permission records included; environment files, virtual environment
  and source-cache absent.

Dependency setup used `--no-cache-dir` because the initial pip operation stalled
after wheel download. Isolated editable build repeated the stall; installing the
build requirements and `editables` into the owned virtual environment then using
`--no-build-isolation` succeeded. No system Python installation was modified.

This is a phase-0 gate, not final acceptance. Agent execution, persistence,
Provider service validation and later phases remain outstanding.
