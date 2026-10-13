# Hook development evidence

Hooks now use native declarations and the common permission-independent configured
action executor, operation ledger, output store, snapshots and recovery reviewer.
The current focused `hooks-tests.xml` report has 23 passing cases. Full regression
passed 549 tests with one Windows file-symlink-privilege skip (`latest-tests.xml`).
The subsequently added explicit CLI retry case also passes in the focused report.
Agent actions remain explicitly unavailable until AgentTeam integration;
they return an error rather than an unimplemented success.

Fixed source vectors in `tests/fixtures/hook-baselines.json` were extracted before
implementation from the author-owned condition, model, configuration and execution
tests. The implementation preserves event names, ==/!=/regex/glob conditions,
and/or groups, once, ordered actions and template names. It replaces the original
in-memory once flags, detached tasks and blocking HTTP worker with durable state
and owned cancellable I/O.

Current-host verified behavior:

- All fifteen events fire through actual application open/close, framework turns,
  model requests, tool approval/execution, file modification, errors and compaction.
  Startup/shutdown belong to the application lifetime; session_start/session_end
  belong to the framework task. A persisted lifetime ID distinguishes normal
  reopen from recovery after process exit.
- Configuration merges user `hooks.json` then trusted project `.pycode/hooks.json`.
  Project hooks are absent until explicit project trust. Matching IDs are overridden
  in configuration order. Unknown actions/events, invalid types, invalid timeouts,
  background pre_tool_use and reject outside pre_tool_use are rejected at the boundary.
- Conditions run in an owned Python process so catastrophic regex evaluation can
  time out. Background hooks evaluate their conditions in the owned background task,
  avoiding a hidden foreground wait. Matching synchronous hooks run in order.
- Prompt actions append hook_context entries; results append hook_result records
  and emit the shared notification event. CLI and Textual consume the same events.
  Existing messages and active Graph state are not rewritten by background jobs.
- Configured Hook commands run through the shared process-group owner and optional
  SandboxBackend. Dynamic template values use child environment variables, avoiding
  shell syntax re-evaluation. Expansion is single-pass, so context text cannot request
  a second environment-secret substitution. Prompt actions reject environment secrets.
- HTTP uses an owned asynchronous client, an action timeout and a response byte cap.
  A real loopback endpoint confirms request delivery and stored response output.
  Uncertain response outcomes leave the operation running and require reconciliation.
  Observed-result reconciliation proceeds without sending the HTTP request again.
- Hook rejection produces a paired native tool error before parent approval/effect.
  Successful configured actions cannot approve the model's tool. Both the CLI and
  Textual path prove the original approval remains necessary.
- Once is scoped to the native session plus immutable rule hash. Reopen, replay and
  execution recovery reuse confirmed results. A startup once result remains verifiable
  even if the app closes before the first user message: lifecycle outputs are full
  objects, rather than references to an unflushed history buffer.
- A real parent-process exit after Hook command confirmation does not repeat the
  effect; the resumed main tool still asks for approval. Background actions do not
  block main results or later tasks, and closing cancels and collects native processes.
  Unknown previous actions cannot be silently retried as a new boundary.
- Main-run Hook configuration hashes are saved before execution and checked on
  reopening/resume, so new rules cannot slip into a saved approval. Public CLI/TUI
  recovery commands handle uncertain background operations once the main task is settled.

Manual recovery accepts `result`, `retry` or `abandon`; retry requires the matching
saved configuration and explicit user selection. Main Graph interruptions use normal
resume/structured answers. macOS physical sandbox tests remain a separate final-host
gate. Ruff, architecture/branding, source hashes, dependency consistency and wheel/sdist
build pass; the wheel includes the condition worker. Reference directories and
development Git remain unchanged.

Scope still pending: actual AgentTeam-backed agent actions, complete MCP integration,
Plan/memory/team/GC modules, and the full project's final platform/provider/Langfuse
acceptance. This evidence does not complete phase 8 or authorize Git history integration.
