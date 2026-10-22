# 当前工具发现流程

2026-10-22：模型工具选择器已删除，本地与 MCP 统一采用名称/一句话分类目录和 ToolSearch 按需加载。详见 [统一工具发现](tool-discovery.md)。

# Component simplification implementation

## Agreed scope

Preserve Team child sessions, mailbox communication, tasks, worktrees, review/merge/cleanup;
Skill reading, creation/update, installation and Bash script execution; MCP stdio/HTTP
tools and ToolSearch/dispatch; synchronous command/prompt Hooks; session history, context,
memory, snapshots, permissions and safe storage collection.

Remove Skill fork, agent/background Hooks, and MCP resources/prompts/elicitation.
User confirmed on 2026-10-22: remove Python resource-tool auto-registration and its launcher; scripts run through Bash.
Use framework SkillsMiddleware, MCPAdapter and Deep Agents routing rather than second loops.

## Implementation gates

- [x] Capture current pycode baseline; distinguish environment limitations from regressions.
- [x] Remove agreed Hook actions/modes, retaining authorization and interrupted-effect records.
- [x] Remove Skill fork without removing ordinary Team delegation.
- [x] Remove MCP resource/prompt/elicitation paths, preserving search and target authorization.
- [x] Flatten Skills/Hooks/MCP/Team around public APIs and meaningful implementation files.
- [x] Add explicit Skill create/update and preparation-only load APIs, with no double model run.
- [x] Converge tool execution through settlement, and processes through the shared runner.
- [x] Reduce redundant adapters/contracts; preserve independently necessary boundaries.
- [x] Update architecture checks, examples, component documentation and distribution resources.
- [x] Run relevant tests, complete suite, lint, typing, architecture and installation checks.

## Baseline and workspace

Implementation targets `/Users/cxg126/Desktop/golang/project/miniclaudecode`.
Existing user changes move the two root Chinese plan documents into `计划/`; leave them intact.
The baseline suite was launched before source edits, with output in
`/tmp/pycode-refactor-baseline.log`. Earlier test counts from other source copies or pasted reviews are
not used as current evidence.

## Architectural decisions

No new public raw-executor shortcut was introduced; model calls retain the settlement path.
Team owns child AgentSession lifetimes; the framework owns each graph execution.
Skill load prepares a request; session execution is explicit. Existing CLI behavior may
compose these two operations once. Stateful resources have one owner and borrowers do not close them.
Flattening is by responsibility, not an arbitrary file/line budget. Small build functions may
share api.py; substantial assembly can retain module.py. Public types never expose internal containers.

## Evidence and remaining work

- Sandbox baseline: 1027 passed / 219 failed / 1 skipped. Many integration failures were
  local socket.bind permission failures, so sandbox results were not used as functional acceptance.
- With local-service permission: retained feature suite passed 115 tests.
- First broad suite: 1214 passed / 3 failed / 1 skipped. The three old UI tests were updated
  to the current warning text and approval-panel collapse behavior; actual behavior was not weakened.
- A later full run exposed a stale MCP header expectation after removing the resource/prompt hint;
  the stable MCP servers prefix was retained. Final complete-suite result is pending below.
- Flattened component sources: Skills 29→10 files, MCP 19→10, Hooks 17→8, Team 14→10.
  These four components went from 6883 to 5926 Python lines (including comments/blank lines),
  while adding local authoring. App went from 27→24 files and 2796→2710 lines.
- Shared schema validator contract replaces duplicate component contracts. Native MCP runtime and
  call binding now use one object. Unused question-extension plumbing and resource-tool calibration
  lookup were removed. Ordinary Team children retain their original session/asyncio ownership.
- Keep legacy synchronous Hook identity zero fields only in serialization so already-confirmed
  once commands are not replayed. Removed modes are still rejected by external config parsing.
- Lint/format, typing and architecture checks passed. Source/wheel builds and distribution-content
  validation passed. Installed-wheel smoke was performed in a separate target directory with the
  existing dependency environment; it is not a clean dependency reinstall or real cloud test.
- Full core flattening was optional in the accepted plan; this implementation completes the primary
  extension/application batches without unnecessarily rewriting core history/model/UI behavior.

## Final acceptance

- Full suite: **1218 passed, 1 skipped, 2 dependency warnings**, 244.31 seconds.
  `/tmp/pycode-final-verified-suite.log`. The skip is the Windows/Linux-specific sandbox vector on macOS.
- Pyright: 0 errors, 0 warnings (`/tmp/pycode-last-types.log`).
- Ruff check/format and `git diff --check`: passed.
- Architecture/dependency/port checks: passed (`/tmp/pycode-last-architecture.log`).
- Native 80×24 approval visibility regression: six consecutive real Textual pilot runs passed.
  Focus now reveals the approval panel after layout without a competing scroll animation.
- Legacy confirmed once-Hook identity: targeted regression passed without repeating command effects.
- Skill Bash approval/denial, authoring revision conflicts and no implicit model run: passed.
- MCP stdio/HTTP, ToolSearch/dispatch, target authorization, large/multimodal outputs and restart:
  covered by the full suite. Unsupported elicitation stops explicitly without an input wait.
- Team mailbox, worktree, child approvals, cancellation and merge tests: passed unchanged in behavior.
- Required feature deletions, framework routing, metadata-only Skill loading and obsolete source
  paths were inspected. No Python Skill loader/worker or agent/background Hook runner remains.
- Source and wheel builds, distribution contents and installed-wheel smoke passed; final UI patch
  is included in the final package verification recorded in the thread.

The optional whole-core flattening described in the proposal remains optional and was not needed
for the completed primary component simplification. No cloud-provider equivalence claim is made.



## Follow-up: synchronous checks, no model fallback, strict current formats

User subsequently requested removal of the dedicated Hook review/retry UI, automatic backup models,
and old-format conversion. This supersedes the earlier compatibility/Hook-review preservation decision.

- Hooks retain synchronous prompt/command checks and pre-tool rejection. Ordinary command failure is
  recorded and exposed in context; pre-tool failure rejects the tool. There is no `hooks`/`/hooks`
  command or reconcile API. An unfinished operation is recorded as unknown and is not replayed.
- Removed ModelFallback, its candidate switching/accounting, runtime state and lifecycle cleanup.
  Same-model retries and explicit manual model changes remain. `fallback_models` is rejected.
- Removed session-v0 upgrading, inferred run ownership, ignored memory migration/receipt fields,
  review→auto conversion, UUID alias lookup and serialized legacy Hook zero fields. Current data
  schemas reject removed/unknown fields; invalid files and stored request payloads are not rewritten.
  Current-format torn-write/branch recovery and GC ancestry headers remain operational.
- Full regression: **1230 passed, 1 skipped**, 210.06 s (`/tmp/pycode-strict-acceptance.log`).
  The skip is the Windows/Linux-specific sandbox case on macOS.
- After rejecting the old `async` field even when false, the focused Hook/storage/tool suite passed
  **72 tests** (`/tmp/pycode-strict-recheck.log`).
- Lint/format, typing, architecture, source/wheel build and distribution checks passed. Tests use
  temporary files and local fixed-response services; no real cloud-provider claim is made.
