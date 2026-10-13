# Phase 7 development acceptance — CLI and TUI integration

The CLI and interactive Textual execution path use public application services.
The phase-7 delivery passes current-host development acceptance. The final full
regression passed 461 tests, with one host file-symlink-privilege skip;
phase-7-tests.xml contains 462 cases and zero failures/errors. Ruff lint/format,
architecture/branding, source hash verification, dependency consistency, and
wheel/sdist build pass. Phase 6 retains its separate phase-6-tests.xml report.

Commands now include run / -p, resume, session list/show/tree/fork, branch, compact,
revert preview/apply/recover, usage / /usage, and events replay. Model selection
also goes through AgentSession. Run creates a new session when no ID is supplied;
--create creates a named session. Other commands require an existing session.
Provider JSON is required for execution commands; views and accounting need no key.

CLI source vectors were captured in tests/fixtures/cli-baselines.json before
implementation from the author-owned CLI/output tests and fixed SHA inventory.
Adaptations replace the source's patched client with actual SDK loopback HTTP,
keep named questions/approval as durable interrupts, and aggregate real per-request
accounting rather than relying on a last cumulative usage event.

Verified integration behavior:

- The real LangChain/LangGraph path streams native Event envelopes and per-request
  usage. A single terminal result is emitted after flushed history, the main Graph
  checkpoint/wait state, and collection of CLI-owned background jobs. Result usage
  contains the main task, session, purpose, origin purpose, and agent groups.
- JSON and text modes suppress intermediate presentation while using the same
  consumers; stream-json emits NDJSON. Exit codes are 0 completed, 2 needs_input,
  1 failed command/model execution, and 130 for console interruption. The command's
  Task cancellation, actual SDK interruption and subsequent recovery are tested;
  a physical Windows console Ctrl+C signal remains separate final verification.
- Approval and AskUserQuestion survive closure/reopen. Resume without an answer
  preserves the interrupt ID. Explicit JSON/file answers continue the pending task,
  preserve operation identity, and do not repeat the first model request or effect.
- Retrying a settled input ID returns its original result and usage without another
  request. Failed/interrupted requests keep unknown tokens; a resumed retry adds a
  separate attempt. Nullable totals and per-field known sums/coverage never claim
  missing values were reported as zero. Branch navigation does not erase costs.
- Session fork/navigation retain original histories. Compact uses real summary
  accounting and preserved raw messages; a new CLI process reconstructs its compact
  context. Review-plan export, explicit dialogue/code/both revert and subsequent
  HTTP execution are tested in temporary Git projects, without changing the real
  development repository or reference projects.
- Durable runtime_events reserve a monotonically increasing sequence per session
  and run before presentation. Two publishers share unique numbers, reopen/resume
  continue the cursor, and failed delivery can be replayed. events --after/--limit
  returns the next cursor. The journal is delivery evidence; canonical messages and
  branch positions still come from JSONL, and execution state comes from checkpoints.
- The session owns the shared journal used by main-runtime summary notifications.
  An actual delayed summary finishes while the user is idle after the main runtime
  closes, publishes ready, and is adopted by a later input. Summary request events
  retain their own job/run namespace and accounting.

Search tools and UI foundations added in the next implementation increment:

- Grep/Glob source vectors precede implementation in search-baselines.json. Common
  Python regex, named groups/lookbehind, Unicode names and splitlines numbering,
  recursive/root/character-class patterns, sorting and empty results are preserved.
  Glob follows the source's Python 3.12 directory-only trailing ** convention.
- Traversal prunes source skip directories, .git (including worktree pointer files),
  .pycode and configured user data. Directory links/reparse points and file links
  are skipped; an explicit outside base is denied. A physical Windows junction
  test proves outside contents are not returned, without depending on file-symlink
  privilege. Source Windows separators are normalized to / in relative results.
- Trusted stdlib-only search workers use isolated Python, fixed executable/script
  argv and UTF-8 JSON input, with no shell interpretation. Grep retains Python regex
  semantics; a pathological regex is interrupted by timeout or explicit cancellation.
  File content is read by logical line; the agent retains bounded streaming buffers,
  while a worker's memory can scale with a single very long line and sorted paths.
- Search output streams to the existing complete-output store, uses a head preview,
  and supports bounded ReadToolResult pagination past a long line. Timeout/failure
  status marks partial output. Filesystem failures are reported rather than silently
  treated as an exhaustive negative result.
- Bash and search share the native process runner. Existing timeout/cancellation and
  child cleanup tests continue to exercise Windows job ownership and POSIX groups.
  Actual public AgentSession plus SDK HTTP verifies both read searches execute
  without approval, preserve distinct call/operation IDs, and do not replay a settled
  input. Six ordinary file/command tools are available together.
- Textual is aligned to the reference uv.lock's 8.2.5, declared in pyproject and the
  dependency lock. Orange dark/light theme tokens and the adapted inline approval
  component have real Textual Pilot keyboard/focus tests. The component emits an
  operation-bound decision and performs no execution. Rich's actual 256-color
  downgrade preserves at least 4.5 contrast for normal text and the primary focus
  color on both backgrounds. These component tests preceded the full application
  increment below and do not themselves establish whole-application acceptance.

Integrated TUI increment:

- `py tui --providers ... [--session ...]` opens the real Textual App assembled in
  container.py. The adapter receives typed public application interfaces and reads
  canonical JSONL projections; it imports no service, workflow or storage adapter.
  SDK/summary callbacks post native Event messages into the App message pump,
  including callbacks with deliberately clean contextvars.
- Actual Textual Pilot plus SDK HTTP/Bash verifies approval before effects, approval
  closure/reopen with the same interrupt, new/clear preserving the old pending
  session, selected provider/model, fork, invalid-open retaining the active session,
  manual summary and new input, three explicitly reviewed revert modes, and subsequent
  execution without replaying previous effects.
- Named questions use tabs, text/single/select/checkbox controls, descriptions and
  Other input. Review is required before submit; field changes invalidate the review.
  Actual Graph answers retain stable operation/interrupt IDs, typed option values and
  one paired result. Closing/reopening and explicit cancellation are tested.
- Ctrl+C stops and collects the active SDK request. Closing the App also collects
  its owned executor and summary resources. Recovery remains available afterward.
  A confirmed tool followed by interrupted model output does not display the old
  consumed approval: the view checks durable operation records before showing waits.
  The Graph still decides execution when resume is requested.
- Steer queued through the live UI is durably accepted and delivered after the
  current model settles. Thinking and nullable per-request tokens render from native
  messages. /usage presents last request, main task, session, purpose and agent
  groups in a scrollable view, retaining known partial sums and coverage.
- UI output exposed a real Windows path-length failure with generated session IDs.
  Temporary output/metadata/artifact filenames now use only a fresh UUID. Formal
  output IDs, hashes and restore/recovery identities remain unchanged; the same
  end-to-end approval case succeeds after this fix.
- Reconciliation and interrupted-revert controls invoke the existing explicit public
  recovery commands; actual crash-to-TUI audits are recorded in the final gates below.
- Deterministic real-framework SVG screenshots of the dark/light question screen
  are generated by `python -m tools.render_tui_demo` under docs/verification. They
  supplement Pilot tests; physical terminal keyboard/viewport acceptance remains
  separate. OS sandbox availability is explicitly displayed as unavailable with
  ordinary permission approval on this current Windows implementation.

Final phase-7 gates added:

- Runtime compaction and UI/CLI occupancy now use the same pure ContextMeter.
  It preserves phase-5 prompt/tool/config fingerprinting and invalidation boundaries.
  Valid server input/output usage anchors the count; appended tail is estimated and
  identified explicitly. Edits/compaction/model/config changes discard obsolete
  anchors. Cached input is counted once. Reports retain window, usable input limit,
  output reserve, summary threshold, fraction, source and anchor request ID. Viewing
  persisted context needs no provider credential or SDK/model construction.
- Actual SDK/HTTP plus TUI usage verifies the reported anchor (25/32000), nullable
  fields, cache ratio, and context displays. Existing background/manual/overflow
  summary tests verify estimator extraction has not changed execution budgeting.
- Actual child exits after a revert branch feed directly into TUI continue/compensate
  controls, restore the selected files/conversation, and permit a later SDK request.
  A real tool side effect followed by process death requires explicit UI review;
  confirming the observed result completes the pair without executing the tool again.
- An actual 80x24 Textual terminal geometry keeps the focused approval within the
  screen, accepts keyboard confirmation, performs the effect and continues the task.
- SandboxBackend represents the real platform boundary. Windows/Linux explicitly
  report unavailable, including when on-auto was requested, retain ordinary approval,
  and do not claim isolation. Actual compiled-Graph/HTTP/Bash verifies this path.
  Seatbelt code ports the fixed local profile/probe design using argv instead of a
  shell-quoted wrapper. Mode defaults off; on retains approval, on-auto may bypass
  only builtin Bash after a successful actual backend probe, behind source deny
  rules. Sandbox failure never retries the command outside isolation. Mode/network
  policy contributes to operation identity to prevent silent approval reuse under
  changed settings. Profile quoting/network/state-write rules and policy contracts
  are tested. These contracts are not macOS physical isolation acceptance.

Remaining work belongs to extension/final acceptance: Plan, Skills/Hooks/MCP,
memory/team/object cleanup, real provider/telemetry accounts, physical macOS Seatbelt
write/network enforcement and terminal checks on target hosts. Final Git history
integration remains prohibited until all development and final acceptance finish.

Current HTTP verification uses loopback protocol fixtures and the public SDKs,
not remote provider accounts. Official OpenAI/Anthropic remote acceptance, Langfuse,
platform-specific physical checks and final Git history integration remain deferred.
