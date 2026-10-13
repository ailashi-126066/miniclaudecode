# Phase 4 implementation evidence — 2026-10-11

Phase-4 development acceptance passed with 300 tests. Ruff, architecture/branding,
source baselines, dependency checks and wheel/sdist builds pass. The follow-on suite
has 310 tests after initial phase-5 source-vector work. CLI/TUI, actual summary
adoption, snapshots, dynamic extensions and remote account acceptance remain later
gates; they are not implied by phase-4 completion.

Verified behavior:

- The configured launcher owns AsyncSqliteSaver and its connection. WAL/FULL SQLite
  and synchronous graph durability coexist with flushed JSONL history. Stable
  `session_id:run_id` threads and persisted run_start identify each execution.
- Operation identity includes owner, call, arguments, workspace and implementation
  hash. States are planned, waiting, running, result_ready and completed; running operations
  cannot silently execute again. Result publication and history binding are separate.
- Real LangGraph task interrupts restore the same permission after close/reopen.
  Approve/deny creates one paired result without repeating model or tool actions.
  Waiting does not create run_end. Answers target interrupt IDs; validation before
  Graph mutation prevents invalid answers from poisoning a saved question.
- Parallel interruptions remain resumable with answers keyed by interrupt ID.
  Result reuse emits matching tool events. Completed results are reused across the
  ledger/JSONL/Graph gaps with idempotent message append, output bind and completion.
- Unknown effects require tool_reconcile. The user may verify a result, abandon or
  explicitly retry. Retry keeps operation identity and uses a new output generation;
  old interrupted objects cannot be overwritten. Verified results use output storage.
- Canonical branch projection replaces Graph messages before requests. A completed
  JSONL assistant is reused when its checkpoint lagged. Incomplete model attempts
  become interrupted records, with nullable usage and a settlement timestamp.
- Recovery checks workspace existence, model/tool fingerprints, branch, log offset
  and source projection. Changed declarations stop pending approval; separate
  sessions can execute concurrently. A saved input without its first checkpoint
  is scheduled only after proving no model attempt or execution history exists.

`tests/support/crash_worker.py` launches a real child that exits with code 73.
Actual HTTP SDK/framework tests reopen it after five boundaries: model streaming,
saved assistant before tools, saved operation result before Graph progress, saved
JSONL result, and file effect before result persistence. Known work is reused;
unknown effects stop for review. Cancellation tests exercise verify/retry/abandon
and resource collection as well as parallel tool recovery. The public
`AgentSession.cancel()` cancels and collects its actual execution owner before
returning; repeated cancellation after cleanup is a no-op.

Additional accepted boundaries:

- `submit_input()` persists immutable native input_queued entries before acceptance.
  They do not become model messages until the settled delivery boundary. Delivery
  is acknowledged by a completed model reply's source leaf, not by popping memory.
  Restart after admission, delivery-before-checkpoint and a consumed steering reply
  preserves FIFO and steer/follow_up ordering without loss or duplicate messages.
- A narrow admission/settlement lock prevents a late input from disappearing during
  run_end publication. Inputs received at Graph completion cause a new framework
  continuation before the application task settles. Retries check both payload and
  owner, and run_end records all processed input IDs and the actual checkpoint ID.
- Completed output publication includes a bounded full ToolResult settlement and
  checksums for the body, settlement and structured artifact. Process death before
  the operation ledger update adopts verified output without rerunning the tool.
  Interrupted/legacy output alone is not treated as proof of completion.
- A completed model request whose reply was not yet checkpointed can be requested
  again. The logical node/attempt is retained and the new request counts as retry;
  both requests' actual usage remain in SQLite. Automatic retry limits still apply
  to each model invocation; no original network connection is resurrected.
- AskUserQuestion ports the local source text vector recorded before implementation
  in question-baselines.json. Text, radio/select, checkbox, descriptions and explicit
  Other values use persistent interrupts. Invalid answers are rejected before Graph
  mutation; cancellation produces an error result. Worker calls cannot prompt the
  user. Real process death while waiting or after answer settlement does not lose
  the question or reask an already confirmed answer.
- Execution ownership is scoped to the original session. Conversation forks cannot
  acquire a parent's pending execution. Navigating to an earlier position in an
  already ended run does not reopen that run; new branch/fork tasks use new threads.
- Real Bash plus OpenAI HTTP tests exposed a missing error/exit representation.
  Native transports without a status field now receive bounded textual status.
  The persisted raw result stays unchanged; byte/line bounds and readback IDs are
  retained, and budgeting uses the same model-visible text.

These are development/runtime checks. Remote OpenAI/Anthropic accounts, Langfuse,
summary adoption, snapshots, dynamic tools, full CLI/TUI and Git integration remain
outside the verified scope of this document.
