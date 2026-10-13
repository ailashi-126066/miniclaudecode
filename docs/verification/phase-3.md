# Phase 3 verification — 2026-10-11

Development acceptance passed with 270 tests. Ruff, architecture/branding, unchanged
source baselines, dependency consistency and wheel/sdist builds pass. Session tree,
JSONL, canonical projection and the phase-3 tool-output chain are implemented.
Summary coordination, object garbage collection and extension hooks remain in their
later phases; no remote account or final Git acceptance is implied by this gate.

Implemented foundation:

- Native immutable header/entry/snapshot contracts independent of LangChain.
  Parent traversal, branch projection, latest compaction and context edits use Pi
  semantics. Source vectors were recorded before implementation in
  `tests/fixtures/pi-session.json`, with original source paths, commits and hashes.
- Empty session opening creates no file. Setup entries remain drafts; the first
  user message publishes the header, drafts and input through a flushed/fsynced
  atomic file publication. Completed messages append without rewriting prior
  records. Position metadata is separately published atomically.
- Nonblocking native file leases enforce a single writer across independent store
  instances. Active execution and model/branch changes are mutually exclusive
  through the shared AgentSession application service.
- Exact repeated input IDs are idempotent; conflicts and another branch's IDs are
  rejected. Settled task retries return the existing final answer by history entry
  reference, without creating another model or tool execution.
- Branch navigation persists the selected leaf while retaining all original
  entries. Fork copies only the requested path, keeps source bytes unchanged and
  records parent session / source entry links in the new header.
- Latest compaction is projected as its checkpoint/summary, retained range and new
  tail. Self-referencing keep-none and repeated compactions follow upstream test
  vectors. Old summaries inside retained ranges do not get injected twice.
- Context edits are branch-local projections. Replaced tool text retains call ID,
  name, error and full output reference; original text and usage stay unchanged.
  State-only entries remain out of model messages; custom messages enter explicitly.
- Truncated tail and native draft-version-0 upgrades preserve the original bytes
  in recovery backups before publication of a corrected active log. Identical
  duplicate records are read once; conflicting duplicates and malformed complete
  records fail explicitly. Unknown state types remain in the native tree.
- History/position commit gaps adopt only valid children extending the recorded
  leaf. This is history reconciliation, not a claim of graph execution recovery.
- Real HTTP/framework tests close and reopen persisted sessions, keep provider
  selection and continue with canonical branch context. Before every model request
  and after settled batches, graph state records leaf, offset, projection hash and
  version; execution checkpoint persistence is still phase 4.

Explicit source differences: unlike upstream's unknown-leaf fallback or orphan
suffix projection, this native format rejects unknown selected leaves and broken
parent chains. Valid-input upstream behavior is preserved; corruption must not
silently select another branch. The native draft upgrade is not Pi session migration.

Completed tool-output acceptance:

- Source vectors in `tests/fixtures/tool-output-baselines.json` precede implementation.
  Pi previews use 2,000 lines / 50 KiB, Read preserves the head and Bash the tail.
- Bash streams complete bytes to persistent objects with bounded buffers. Data and
  metadata are flushed/fsynced before publication. Stable IDs record owner, call,
  operation, bytes, lines, SHA-256, error state and original entry. Small results
  remain inline; large structured artifacts are retained outside message bodies.
- Actual command tests cover 8,000-line output, timeout, cancellation, descendant
  cleanup and output continuing after the parent exits. Windows job ownership is
  process cleanup; it does not claim an OS sandbox.
- Read uses bounded file ranges without copying existing source files. ReadToolResult
  validates branch scope, fork ancestry, source identity, size and content hash.
  Hashing and readback use the same handle. Giant single lines have byte continuation;
  mid-character cursors are rejected. Missing/corrupt objects never rerun tools.
- Readback creates a new ordinary result without undoing old context edits or
  repeatedly producing only a path. Full originals survive edits and repeated
  compaction. Fork can access copied references but cannot access other branches.
- OpenCode selection skips two recent user regions, scales 40k/20k budgets, selects
  whole boundary entries, protects Skills and ignores unfinished/orphan results.
  Native edits retain pairing, errors and IDs. Repeated cleanup is idempotent and
  branch-local; restart and next real framework requests use persisted decisions.
- No garbage collector is enabled. Retaining all objects prevents premature loss;
  reference marking and 30-day quarantine still require later acceptance.

Forced-exit recovery is covered in `phase-4.md`. Actual summary adoption must still
verify prompt/tool checkpoints, concurrent eviction and summary reference retention.
