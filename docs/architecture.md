# Final architecture and ownership

`app/container.py` is the sole composition root. It constructs concrete adapters and injects
them into services/workflows. `app/runtime_launcher.py` owns the active run, live waits,
summary and Hook lifetimes through explicit construction callbacks. `app/background_models.py`
owns summary and memory model lifetimes; neither implements another Agent loop.

Dependency direction is app → adapters/services/workflows, workflows → services/ports/core,
services → ports/core, adapters → ports/core. Core is pure data/policy. Framework message
conversion and decorators stay in workflow/adapter boundaries. Imports construct no resources.

`workflows/agent.py` creates the sole `deepagents.create_deep_agent` graph. `state.py` defines
its minimal state/context. `history.py` restores canonical projected input and records model
responses before tools. `settlement.py` validates, authorizes, invokes the framework handler,
orders durable result settlement, binds objects/snapshots and emits final events. Native tools
can return Command state updates; settlement retains todo updates.

FilesystemMiddleware uses PycodeBackend and explicit routes: `/workspace/`, `/plans/`,
`/skills/<source>/`, `/large_tool_results/`, `/conversation_history/`.
Workspace paths, hashes and locks are enforced by the Backend. rg supplies real regex/glob
search, ignoring internal state and default hidden paths; complete large results are preserved
before native display truncation. SkillsMiddleware owns loading/disclosure, including budgeted
catalogue reload through its public methods. LangChain MCPAdapter owns MCP connections/protocol.

Main-chat InMemorySaver exists only for the live run. JSONL is the sole restart authority.
Lost calls, memory tools included, get one interrupted result; no old approval or disk Graph
state is reused. Recovery never
reexecutes old business tools. EventStream isolates failed consumers; closed CLI output requests
controlled cancellation. JSONL contains one original result; large bytes live in one scoped
object. Memory/summary/Plan metadata reference original facts rather than another conversation.

Settlement normally follows declared tool order. An interrupted call does not hold completed
later calls: their JSONL results settle before the native graph returns the waiting state.
Resume clears only transient interrupt barriers; confirmed results are not executed again.
The repeated-failure barrier applies only to the matching name/arguments, and yields when
that call interrupts. Each completion event and Hook retains its own operation identity.

Harness summarization is excluded through its public profile. The pycode 80% background
summary uses source/configuration hashes and two-phase safe adoption. Effective native/dynamic
tool definitions and system-prompt footprint are recorded for runtime/UI capacity consistency.
Project prompt snapshots refresh only at new tasks. Store entries are immutable, indexed once
by ID; active-path IDs and projection revision caches avoid deep-copying complete history.

AgentTeam owns asyncio child AgentSessions, a bounded atomic task board and durable inboxes.
A dedicated team JSONL contains atomic transitions, separate from the lead branch leaf;
child JSONL sessions link their lead and remain available after worktree removal.
HistoryMiddleware flushes inboxes before model calls and before closing input admission.
Wait delivery is acknowledged only after a successful ToolResult with message IDs is persisted;
idle wake inputs are confirmed from JSONL. Restart marks active children interrupted without reruns.
Readonly children expose read-only tools. Worker file tools target an owned worktree;
commands need an available strict sandbox and explicit approval, and unisolated MCP/Hook actions
are not loaded. Parent custom/Skill denials remain effective. Every merge has source-tree and
target-HEAD-bound approval outside Auto Plan, then guarded Git integration and cleanup.
Fork Skills and Hook delegates share this coordinator, preserving full/recent/no parent context.
Usage retains purpose/agent/session identity and sums children from durable team_agent links.
See [the reviewed design](design/inproc-team.md) for cancellation, conflict recovery and limits.

| Team port | Implementation |
| --- | --- |
| ChildSessions / ChildSession | app.container.ConfiguredChildSessions / services.agent_session.AgentSession |
| Worktrees | adapters.team.worktrees.WorktreeManager |
| EventSink | services.events.EventStream |
| SessionStore | adapters.storage.session.JsonlSessionStore |

Plan is an exact writable document with run-bound approved hash; restart expires approvals.

Memory has two parts. AGENTS.md instructions (`~/.pycode/AGENTS.md`, plus the project's
`AGENTS.md` only with `--trust-project`) load through Deep Agents `MemoryMiddleware` with a
read-only allowlisted
backend. They are read once per run into private graph state and appended to every model call's
system message, so they never enter JSONL history and compaction/summaries cannot drop them;
the harness system footprint recorded for capacity includes them.

Long-term memory uses LangMem directly; pycode owns only storage location, trigger timing,
viewing/editing and undo. `services/memory.py` (MemoryService) recalls entries before a task
(all of them up to 20, otherwise a metered selector picks up to 10), schedules one background
`create_memory_store_manager` pass when a run completes or after manual/automatic compaction, logs every
change and undoes the latest automatic pass. The model also gets LangMem
`manage_*_memory`/`search_*_memory` tools through ordinary settlement and MemoryToolGate.
Background memory models always run with thinking disabled (forced tool choice conflicts with
DeepSeek thinking); failures are logged, never retried and never reach the conversation.
Automatic passes write the user scope and, for a trusted project, the project scope. The write
filter (`core/memory.py`: length, per-scope count, credential patterns) applies to every write.
JsonMemoryStore keeps one atomic `memory.json` per scope with prefix and simple text search.

GC marks all durable references and
live/uncertain writers before 30-day quarantine/purge; memory directories are explicitly
excluded from managed objects. Worktree integration/cleanup stays separate.
Seatbelt profiles deny by default: reads cover the workspace, system directories, temp,
the running interpreter's prefixes/import roots and git global config; later rules deny
~/.ssh, ~/.aws, ~/.gnupg, ~/.config, ~/.pycode and every .env/.env.* except a workspace
.env.example. Writes stay in the workspace outside .git/.pycode and dotenv files (so they cannot
be renamed out of the read deny). Toolchains and caches under HOME are not readable.
`on-auto` approves only commands whose every segment passes the pure core allow list
(`core/command_policy.auto_approvable`, quote-aware; unquoted globs in find/rg/git arguments are
rejected); others use the ordinary approval interrupt. Deny patterns are defence in depth ahead of both.
Evaluation runs the same harness in isolated declared-file workspaces and tests independently.

Build/tests operate here without initializing the project Git directory. Reference projects
remain read-only. Temporary Git/worktree operations are confined to tests-owned roots.
Evaluation lives in the separate `evals/` package (pycode never imports it) and keeps its
cache/work/results under `evals/` or `tests/.runtime-tests`.

Review fixes: serial tools wait for preceding durable settlement
or interrupt, rather than racing a lock and an outdated failure count. Live tool tasks are
owned and collected when persistence or a critical synchronous Hook fails.

MCPAdapter-produced BaseTools reach the graph directly. The connection gateway rechecks pinned
schemas/annotations; one boundary converts the native result to its canonical JSONL fact.
New runs refresh discovery without mutating a live run's declaration snapshot. No raw call-tool
dictionary round trip or random inner call ID remains.

rg uses its public NUL filename framing and sorted output. Python drains bounded chunks, retains
a bounded preview, and streams complete oversized matches into the existing output store. Bash
and Python progress uses the same process owner and bounded event chunks. Actual Textual render
errors are isolated in the message pump; immutable presentation views and incremental sidebar
nodes avoid repeatedly copying or rebuilding completed history.

Evaluation retains version-linked evidence before removing baseline/workspace pairs. Its optional
SDK uploader observes measured summaries after execution; submission status is distinct from
independently verified remote ingestion. Historical audit scripts and test-owned environments
are preserved outside normal collection and distributions; their confirmed cases are covered
by production regression tests.
