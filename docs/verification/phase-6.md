# Phase 6 implementation evidence — 2026-10-11

The snapshot and revert chain passes phase-6 development acceptance on this host.
The final full gate passed 393 tests, with one physical symlink test skipped because
this Windows host lacks symlink creation privilege (WinError 1314). The JUnit record
is phase-6-tests.xml. The phase is checked in the plan with this explicit limitation;
the skip is not presented as a passed symlink restore test. Cross-platform physical
checks and final real-repository acceptance remain outstanding for final acceptance.
Ruff lint/format, architecture/branding, 229 local source hashes plus both fixed
upstream archives, dependency consistency, and wheel/sdist build also passed.

Verified:

- Source vectors were recorded before the Git backend in snapshot-baselines.json
  from fixed OpenCode commit 055d95bb7e278c94baf06235a52cac79dd13ba67. No Effect
  dependency is introduced. Python locks, leases and managed I/O own lifetimes.
- Git discovery is read-only. Non-Git capture returns unavailable without creating
  storage or initializing the project. Only temporary test projects are initialized.
  Git snapshot storage is a separate bare repository, index and ref namespace.
- Capture hashes working-tree bytes without Git filters, stages blobs into its own
  index and calls write-tree. Project index/HEAD/stash bytes/refs remain unchanged.
  Unicode names, create/change/delete, binary files and location scopes are tested.
- Ignore policy: project .gitignore and local info/exclude are respected, pycode
  state/data and restore staging are excluded; untracked files over 2 MiB are
  skipped, tracked large files retained. Global/system Git configuration is isolated
  to avoid arbitrary hooks/filters and make capture deterministic.
- Snapshot records have checksums and registered tree IDs; content objects are
  pinned in the independent repository. Preview contains per-path diffs, versions
  and conflicts. Expected versions come from the path's last observed operation,
  preventing unrelated later snapshots from accepting external edits silently.
- Selective restore rejects path escape, directory links, stale versions and any
  global preflight conflict before modifying selected files. It never uses a project
  reset, checkout, commit or stash; unrelated paths remain intact.
- Restore publishes a progress record and compensation tree before writes. Files
  stage before replacement. Real child-process death after the first file resumes
  by recognizing completed target versions; compensation restores the pre-revert
  state. Original recovery records become compensated, rather than replaying twice.
  Reverse transactions are also persisted and linked before compensation writes.
  A real exit after the first compensation file resumes the same reverse ID,
  rejects switching to the forward action, and leaves exactly the original/reverse
  records with no owned staging files abandoned.
- The common ToolExecutor captures before/after mutating tools and Bash. Durable
  operation records and native file_snapshot entries associate trees, changed paths,
  message entry, run and operation with the session branch and end checkpoint.
- AgentSession preview_revert/revert supports dialogue, code and both. Actual Bash
  plus native HTTP tests cover all modes, external conflicts and continuing afterward.
  Dialogue navigation keeps the original branch; code restoration adds an explicit
  model reminder and invalidates mismatched background summary caches.
- recover_revert finishes a journaled file restore's pending dialogue step through
  the public application service. Session-owned transaction IDs and complete plans
  survive dialogue branching. Finished retries preserve subsequent dialogue and
  external file edits; altered plans under the same identity are rejected. New
  code previews recognize versions from prior restores, retaining external-edit
  conflict detection.
- Actual child exits cover after-start, before/after dialogue branch publication,
  and after the code reminder, for all applicable modes. Each boundary is checked
  for continue and compensate. Compensation also restores the original dialogue
  position; additional exits during compensation verify persistent recovery choice,
  reject an action switch, and preserve idempotence after a second restart.
- Pending reverts block execution, queue submission, branch/model changes,
  compression and further reverts. Parent executors are not inherited by forks.
  Pending tool approvals cannot be bypassed by compression or revert. Actual public
  AgentSession plus Bash/HTTP tests verify no request before recovery, and successful
  continuation after journal recovery for dialogue/code/both (local protocol server;
  this is not remote provider-account acceptance).
- Existing CLI model selection now delegates to AgentSession; red-then-green CLI
  tests prove it cannot bypass a pending revert or model/tool execution. Earlier
  native dialogue-only records without a transaction ID remain recognized and
  recoverable without requiring Git or a nonexistent filesystem restore record.
- Read/Write/Edit port local source behavior with existing attribution preserved.
  Existing writes require a read-version hash; external change, missing or duplicate
  exact match is rejected. Path locks cover the full read/replace/write sequence;
  publication is flushed and atomic. A real approval restart restores read receipts
  from branch history and produces an Edit snapshot boundary.

Platform notes: file symlink contents are recorded as Git mode 120000 without
following the target. Physical symlink creation/restoration needs host privilege;
this host's real test is skipped. POSIX executable bits are saved; on Windows,
tracked index mode is used because ordinary Windows stat does not expose POSIX
execute permission. Metadata and conditional implementation are not substitutes
for physical platform validation.

Attribution limitation: snapshots describe the workspace before/after an observed
operation. Concurrent external writes during that operation cannot be assigned to
one author reliably. Review the diff; later external edits cause conflict rejection.
Final real repository/worktree acceptance remains deferred until all development
and acceptance gates pass, before the separate Git history integration phase.
