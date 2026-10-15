# Final architecture and ownership

`app/container.py` is the sole composition root. It constructs concrete adapters and injects
them into services/workflows. `app/runtime_launcher.py` owns the active run, live waits,
summary and Hook lifetimes through explicit construction callbacks. `app/background_models.py`
owns summary and per-job memory model lifetimes; neither implements another Agent loop.

Dependency direction is app → adapters/services/workflows, workflows → services/ports/core,
services → ports/core, adapters → ports/core. Core is pure data/policy. Framework message
conversion and decorators stay in workflow/adapter boundaries. Imports construct no resources.

`workflows/agent.py` creates the sole `deepagents.create_deep_agent` graph. `state.py` defines
its minimal state/context. `history.py` restores canonical projected input and records model
responses before tools. `settlement.py` validates, authorizes, invokes the framework handler,
orders durable result settlement, binds objects/snapshots and emits final events. Native tools
can return Command state updates; settlement retains todos/async-task updates.

FilesystemMiddleware uses PycodeBackend and explicit routes: `/workspace/`, `/plans/`,
`/skills/<source>/`, `/large_tool_results/`, `/conversation_history/`.
Workspace paths, hashes and locks are enforced by the Backend. rg supplies real regex/glob
search, ignoring internal state and default hidden paths; complete large results are preserved
before native display truncation. SkillsMiddleware owns loading/disclosure, including budgeted
catalogue reload through its public methods. LangChain MCPAdapter owns MCP connections/protocol.

Main-chat InMemorySaver exists only for the live run. JSONL is the sole restart authority.
Lost calls get one interrupted result unless a verified memory commit receipt can restore
their exact confirmed result; no old approval or disk Graph state is reused. Recovery never
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

Native sync task uses independent read-only sessions. Fork Skills inherit full/recent/no parent
context without loading into the parent. Async Subagents use the public native tools and Agent
Protocol; local hosting invokes the same AgentSession, owns cancellation, retains JSONL/worktrees,
and has no worker mailbox/cursor. TeamManager adds dependency policy and concurrency, never a
second tool/model scheduler. Usage retains purpose/agent/session identity and known team totals.

Plan is an exact writable document with run-bound approved hash; restart expires approvals.
Memory uses official LangMem manage/search tools and Store Manager over JsonMemoryStore.
Each namespace owns one active memory.json; related changes, processed source ranges and
commit receipts publish atomically per scope. Jobs own immutable sources, staging, model
observers and per-request accounting. Rule feedback selects single/multi prompt_memory;
inferred rules require a fixed, tool-free comparison and exact version-bound runtime permit.
Scope moves use ordered leases, copy-first publication and persistent receipts, without a
cross-file transaction claim. LegacyMemoryRecovery only finishes existing old transactions
before one-way migration; no runtime Markdown reader, writer or candidate approval remains.
GC marks all durable references and
live/uncertain writers before 30-day quarantine/purge. Worktree integration/cleanup stays separate.
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
remain read-only. Temporary Git/worktree/evaluation operations are confined to tests-owned roots.

Review fixes: one TeamManager is injected into both session controls and live runs. The same
Plan/concurrency policy governs CLI, TUI, dependency starts and native model tools. Worker waits
are surfaced by native check results; explicit user answers use native update on the same thread.
Ordinary update/cancel expires the old wait. Serial tools wait for preceding durable settlement
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
