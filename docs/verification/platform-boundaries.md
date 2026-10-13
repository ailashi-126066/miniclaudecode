# Target-platform and credential boundaries

The current host is Windows 11, Python 3.12.10. Actual commands, JUnit counts,
source checksum, dependency versions and installation paths are in
`final-verification.json`. Source changes and previous failures remain in the
earlier reports; their numbers do not describe the final revision.

The existing physical file-symlink test skips because this Windows account lacks
the required privilege (WinError 1314). The rest of the snapshot/worktree tests
use real Git repositories inside `tests/`. No project Git initialization,
source-reference changes, project commits or final history integration were performed.

OpenAI and Anthropic credentials are not configured. Their exact deferred checks:

```powershell
.venv\Scripts\python.exe -X utf8 tools/verify_live_provider.py --provider openai --model gpt-4.1-mini --base-url https://api.openai.com/v1 --max-output 256 --output docs/verification/live-openai-target.json
.venv\Scripts\python.exe -X utf8 tools/verify_live_provider.py --provider anthropic --model claude-sonnet-4-5 --base-url https://api.anthropic.com --max-output 256 --output docs/verification/live-anthropic-target.json
```

Remote MCP requires a real endpoint and its credentials. Local stdio and HTTP
servers, direct/dispatch, resource/prompt access, errors, reconnection, closure,
media and native elicitation are tested with real transports. Those results do
not verify a remote server that has not been configured.

Langfuse remote ingestion needs `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY` and
optionally `LANGFUSE_HOST`; local HTTP tests verify aggregate-only payloads.
Once configured, append `--langfuse` to the fixed evaluation command below.
No source code, prompts or tool-result bodies are included in that payload.

macOS physical checks remain unverified. Copy the source, not the Windows venv.
Run from the project root with Python 3.12, Git and ripgrep available. The constraint
file pins shared packages without requesting Windows-only distributions:

```bash
python3.12 -m venv .venv-macos
mkdir -p tests/dependency-artifacts tests/evals/cache
.venv-macos/bin/python -c "from pathlib import Path; Path('tests/dependency-artifacts/constraints-macos.txt').write_text(Path('requirements-dev.lock').read_text().replace('langchain[mcp]', 'langchain'))"
.venv-macos/bin/python -m pip install --no-cache-dir -c tests/dependency-artifacts/constraints-macos.txt '.[dev,team]'
.venv-macos/bin/python -m pip check
.venv-macos/bin/python -X utf8 -m pytest -q --basetemp tests/.t/macos --junitxml docs/verification/macos-tests.xml
.venv-macos/bin/python tools/verify_seatbelt.py --output docs/verification/seatbelt-macos.json
```

The Seatbelt command actually launches sandboxed Bash, verifies writes inside
and outside the selected workspace, then checks denied and explicitly enabled
loopback networking. Its Windows invocation returns `unverified`.

Prepare the fixed benchmark cache if it has not been copied:

```bash
git clone --filter=blob:none --no-checkout https://github.com/Aider-AI/polyglot-benchmark.git tests/evals/cache/polyglot
git -C tests/evals/cache/polyglot sparse-checkout init --cone
git -C tests/evals/cache/polyglot sparse-checkout set python
git -C tests/evals/cache/polyglot checkout --detach 7e0611e77b54e2dea774cdc0aa00cf9f7ed6144f
.venv-macos/bin/python -X utf8 -m pycode --memory-mode off eval --providers tests/evals/providers-final.json --work tests/evals/work/macos-smoke --results tests/evals/results/macos-smoke
.venv-macos/bin/python -X utf8 -m pycode --memory-mode off eval --providers tests/evals/providers-final.json --work tests/evals/work/macos-full --results tests/evals/results/macos-full --full
.venv-macos/bin/python -X utf8 -m pycode --sandbox on tui --providers tests/evals/providers-final.json
```

Set the referenced DeepSeek credential on that host. The fixed model is
`deepseek-flash`; the manifest has three smoke tasks and twelve full tasks.
Record target-terminal scrolling, original-history cards, folded output/readback,
Unicode input, keyboard shortcuts, AskUser, three Plan choices, memory edits and
native MCP forms. Headless Textual Pilot tests are separate evidence and do not
claim target-terminal or OS-enforcement verification.
