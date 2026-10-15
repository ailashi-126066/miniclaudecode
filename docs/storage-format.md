# Storage authorities and recovery

```text
~/.pycode/
  model-catalog.json
  mcp.json / hooks.json / team.json
  skills/
  memory/                       # memory.json, versions/, jobs/, moves/
  projects/<normalized-workspace-hash>/
    runtime.sqlite              # model_requests accounting only
    sessions/<opaque-session-id>/
      entries.jsonl             # immutable original messages/tree/product facts
      position.json             # derived active leaf, revision and byte offset
      writer.lock
      tool-results/             # metadata + optional one complete byte/artifact object
    summaries/                  # source/ready result/invalidation references
    snapshots/repository.git/   # independent tree objects and refs
    worktrees/                  # registry and cleanup audit; no automatic commits
    quarantine/sessions/        # explicitly deleted conversations
  quarantine/objects/           # 30-day content-object quarantine
<workspace>/.pycode/
  plans/<session-id>/<plan-id>.md
  memory/
  skills/ / mcp.json / hooks.json / team.json
```

Main Graph checkpoint and progress events are memory-only. runtime.sqlite has no current
tool-operation, stream-event, approval or main-chat checkpoint tables.

No storage migration or legacy-format compatibility code remains. Only the current formats are
read: session headers branded `pycode`, output metadata with an empty preview plus
`preview_sha256`, revert journals whose `revert_started` entries carry `transaction_id` and a saved
plan, and JSON memory (`memory.json`). Older runtime tables, `py`-branded headers, settled output
records, pre-transaction revert journals and per-record Markdown memory are no longer converted or
recovered. Memory snapshots still accept the optional `migration` receipt field so files published
by the former one-way migrator keep decoding; nothing writes, verifies or protects its backups.

JSONL model responses precede tool execution. Final ToolResult precedes object binding, snapshot
boundary and finish events. A streamed object can be durable before its JSONL fact; restart treats
that gap as interrupted, retains byte evidence and never promotes it to a confirmed success.
Partial filenames are operation-scoped so restart can recover observed bytes. Small completed
output uses original JSONL text; metadata has checksums/references, no saved ToolResult or preview
body. Large output has one complete file plus bounded JSONL preview. Readback validates owner,
current branch/fork ancestry, source entry, identity, byte/artifact hash and UTF-8 ranges.

Opaque ID is stable across renames. session_metadata adds display names; no log directory uses
a title. Branch position is a derived file. Fork keeps parent/source references and original IDs.
Missing tools are paired once by assistant and call identity. Lost run/queued input facts remain
visible, but old tools and waits cannot resume after process exit.

Summary objects are produced independently and adopted only against matching source/config/branch
boundaries. Plan approval stores exact content/hash; changed files require a new review. Memory
snapshots are distinct from conversation summaries.

GC inventories managed roots, treats corrupt/partial/live state conservatively, and marks original
branches, fork ancestry, output/summary/Plan/memory/team references. Unreferenced objects move to
quarantine first; renewed references restore content, sessions require explicit restoration. Purge
after 30 days never targets the user's Git repository. Snapshot Git ref removal/GC is isolated.
Worktrees are retained while dirty, active, cancelled, unintegrated or unverified; checked clean
integration and stopped status are required for removal. Cleanup is audited and scanned every 30 days.

GC rechecks current references and active owners under its mutation lease before destructive
actions. Audit records retain the actual source session/entry/timestamp when known, plus a
separate reference-at-collection flag.

Review fixes add no main-chat persistence authority. Team task facts may contain a current
Worker pending question plus an explicit worktree start choice; cancelling/updating clears that
live wait. Human answers are validated against a fresh native check and cannot resume a lost
executor. Plan state is rebuilt when dialogue revert/recovery changes the active path.

Evaluation results use schema version 2. One fresh run directory stores `results.jsonl`, per-task
summaries, `summary.json`, source/dependency manifests, bounded patch/log/JUnit evidence and
compressed session JSONL. Source hashes cover actual package bytes; the verification report also
hashes tests/tools and the dependency lock. Exported traces explicitly state whether every raw
entry was retained and that ephemeral output objects were removed with the workspace. Earlier
reports are immutable historical evidence and are never rewritten by normal test execution.

JsonMemoryStore binds configured namespaces
(non-path user/project identities) to separate `memory.json` files. Schema version 1 contains
namespace, revision, typed records with timezone timestamps, and committed source-batch receipts.
Duplicate keys, missing required fields, future versions and corrupt files fail without overwrite.
Updates use the existing FileLease and atomic_write; prior whole snapshots are inactive JSON
version backups. A job-owned BaseStore view stages component writes and freezes before commit.
Each scope commits independently with its observed revision, content and source progress together.
Replayed source receipts cannot undo a newer correction. Directory search uses a metered model,
never embeddings, keyword ranking or full-body injection into selection prompts.
UUID spelling aliases resolve to the existing key, including official tools updating hex IDs.
GC uses the same strict snapshot validation, scans active snapshots, version backups, pending jobs
and scope moves, and protects active memory writers. Mutation rechecks the inventory before moving
or purging objects.


New memory commits capture a trusted original-branch evidence snapshot before model review.
Tool quotes use checked complete output readback, with bounded character ranges and no preview
fallback after corruption. Physical source/role/scope checks precede a metered support verdict;
actual source presence does not alone authorize a new claim or deletion. A job-owned write permit
binds accepted changes and their observed target hashes. Associated changes validate together,
independent valid groups can commit, and rejected groups cannot advance the whole source batch.
Progress IDs and character positions must be real, contiguous captured sources. The existing
atomic JSON commit remains the sole publication path. Durable rejected-group recovery and the
application memory-service/tool switch are still pending integration.
