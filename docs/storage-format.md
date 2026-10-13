# Storage authorities and recovery

```text
~/.pycode/
  model-catalog.json
  mcp.json / hooks.json / team.json
  skills/
  memory/                       # approved Markdown + metadata, candidates, versions, transactions
  projects/<normalized-workspace-hash>/
    runtime.sqlite              # model_requests accounting only
    sessions/<opaque-session-id>/
      entries.jsonl             # immutable original messages/tree/product facts
      position.json             # derived active leaf, revision and byte offset
      writer.lock
      tool-results/             # metadata + optional one complete byte/artifact object
      migration-backups/        # inactive exact pre-migration bytes
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
tool-operation, stream-event, approval or main-chat checkpoint tables. Migration takes an exact
SQLite backup before dropping obsolete tables, retains model accounting, and backs up old
output/header records before normalizing the final representation.

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
records use evidence, optimistic content hashes, version backups and prepared transactions; reads
recover/validate consistent content and metadata. Candidate scope/contents require explicit review
when ambiguous/conflicting. Memory snapshots are distinct from conversation summaries.

GC inventories managed roots, treats corrupt/partial/live state conservatively, and marks original
branches, fork ancestry, output/summary/Plan/memory/team references. Unreferenced objects move to
quarantine first; renewed references restore content, sessions require explicit restoration. Purge
after 30 days never targets the user's Git repository. Snapshot Git ref removal/GC is isolated.
Worktrees are retained while dirty, active, cancelled, unintegrated or unverified; checked clean
integration and stopped status are required for removal. Cleanup is audited and scanned every 30 days.

GC rechecks current references and active owners under its mutation lease before destructive
actions. Audit records retain the actual source session/entry/timestamp when known, plus a
separate reference-at-collection flag. Memory removals use prepared transactions too; restart
finishes a partial content/metadata move before reading the index.

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
