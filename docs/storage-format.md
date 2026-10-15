# Storage authorities and recovery

```text
~/.pycode/
  model-catalog.json
  mcp.json / hooks.json
  skills/
  AGENTS.md                     # optional user instructions (MemoryMiddleware)
  memory/                       # memory.json, settings.json, changes.jsonl, memory.lock
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
  memory/                       # memory.json, memory.lock
  skills/ / mcp.json / hooks.json
<workspace>/AGENTS.md           # loaded by MemoryMiddleware only with --trust-project
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
Fields left by the removed memory pipeline (`processed_batches`, `group_receipts`,
`tool_receipts`, `move_receipts`) are ignored on read and dropped on the next write; old
`versions/`, `jobs/` and `moves/` directories are no longer read.

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
branches, fork ancestry, output/summary/Plan/team references. Memory directories
(`~/.pycode/memory/`, `<workspace>/.pycode/memory/`) are explicitly excluded: no path under them
can become a managed object, so GC never moves `memory.json`, settings or the change log. Unreferenced objects move to
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

JsonMemoryStore (a LangGraph BaseStore) binds configured namespaces (non-path user/project
identities) to separate `memory.json` files. Schema version 1 contains `namespace`, `revision`,
optional `migration` and `records`: `{key: {value: {kind, content}, created_at, updated_at}}`.
LangMem writes `{kind: "Memory", content: {content: text}}`; older Semantic/Episodic/Procedural
records stay readable. Existing hex UUID IDs resolve through their UUID-equivalent hyphenated
form when LangMem manages them; writes retain the existing key. Each write takes the scope's `memory.lock` FileLease and publishes the
whole file with atomic_write. Duplicate keys, invalid records and corrupt files fail without
overwrite. Search supports namespace prefixes, dotted equality filters and simple token overlap
for text queries; there are no embeddings, staging views or job leases. `settings.json` in the
user scope holds the `auto`/`off` mode.

`~/.pycode/memory/changes.jsonl` is the append-only memory change log (one JSON object per line;
a torn final line is skipped):

```json
{"id": "<hex>", "at": 1760000000.0, "origin": "consolidate|tool|edit|delete|clear|undo",
 "reason": "session_end|compaction|manual", "session_id": "...", "run_id": "...",
 "undoes": "<id, undo only>", "error_code": "<on failure>", "rejected": [...],
 "changes": [{"scope": "user|project", "namespace": [...], "key": "...",
              "before": <record|null>, "after": <record|null>}]}
```

`memory undo` restores the `before` records of the newest `consolidate` entry for the current
scopes that has not been undone, and refuses if any of those entries changed since (`after`
no longer matches). The comparison and restore hold every affected scope's file lease, so
another session cannot change a record between the check and restore. Repeated writes to one
entry within a pass are logged as its original `before` and final `after` state. If a disk
error prevents restoring a later scope, changes already published are logged with
`attempted_undo`; `undoes` is recorded only after every affected scope has been restored.

## Local team records

The lead project contains a hidden `team-<lead hash>` session ledger with its own writer lease.
Each `team_transition` atomically stores complete `agents`, `tasks` and `messages` snapshots;
replay applies the last record per ID and skips malformed transitions as a whole.
Lead history contains `team_agent` child links, and each child history starts with `team_parent`.
Child histories use their own workspace's StorageLayout outside the Git worktree and share
the lead runtime request database, so cleanup preserves conversations and usage accounting.

Message delivery uses pending/delivering/delivered/dropped plus receiver run/input IDs.
Successful wait_agents results carry `details.team_message_ids`; interrupted or synthetic results
do not acknowledge delivery. Idle wake inputs record message IDs and retain sender/kind text.
On restart active children become interrupted, unfinished claims reopen, old child messages
are dropped, and notifications wait for the next user-started run. No child or lead auto-runs.
Worktree recovery flags are inspected and shown without automatically merging, aborting or cleaning.
