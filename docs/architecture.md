# Final architecture and ownership

`app/composition/` is the only cross-feature composition root. Its runtime, session,
models, context, memory, team, UI and shared-capability modules build concrete adapters and
inject their capabilities. `app/runtime_launcher.py` owns active runs, live waits, summary
and Hook lifetimes. Context and memory composition own their background model lifetimes;
none implements a second Agent loop.

Source is organized as `kernel`, `agent`, `session`, `context`, `models`, `tools`, `sandbox`,
`snapshot`, `memory`, `skills`, `mcp`, `hooks`, `plans`, `team`, `ui` and `app`. The old global
layer packages are removed. Each feature has pure `domain/`, consumer-owned `ports/`,
framework-free `services/`, concrete `adapters/`, and a static public `__init__.py` with explicit
`__all__`. Cross-feature imports use that public API; only composition imports another
feature's adapters. Adapter classes, converters and middleware are never re-exported as
public feature APIs. Imports construct no resources.

The enforced DAG is recorded in `docs/design/feature-dependencies.json`: kernel has no feature
dependencies; sandbox/snapshot/models/mcp depend only on kernel; skills/plans/hooks add sandbox;
context adds models/mcp/skills; tools adds sandbox/snapshot/skills/plans; agent/team/memory
compose these capabilities; session and UI sit above them; app composes everything. Domain
imports cannot reach I/O, including through kernel's public API, and domain/ports/services do
not import frameworks. The checker follows relative imports, aliases and public factory
origins, rejects unexported names, delayed imports and hidden dynamic pycode APIs, and checks
both module and feature graphs for cycles (including TYPE_CHECKING imports).

`agent/adapters/agent.py` builds the sole `deepagents.create_deep_agent` graph. Framework state
is separate from the framework-free `agent/services/context.py` RunContext and its consumer
Protocols. History middleware records model responses before tools; tool settlement owns
only its private failure-state fields. Composition injects model converters, calibration,
request metering, Skills and tool middleware. A bound message publisher preserves JSONL →
output binding → snapshot fact → completion-event order; tool lifecycle callbacks run after
durable publication. Revert orchestration and conversation transactions belong to session;
snapshot owns only Git/file versions and restores.

Skill and Plan backends delegate to explicitly injected filesystem operations, rather than
inheriting another feature's adapter. Skill files remain read-only and version-pinned. Plan
resolution restricts every operation to the active file, and its search scope makes rg scan
only that file. Python Skill sandbox grants are issued only after catalog verification for
that exact source root; worker write roots and credential/environment restrictions remain.

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

Plan is an exact writable document with run-bound approved hash; restart expires approvals.

Memory has two parts. AGENTS.md instructions (`~/.pycode/AGENTS.md`, plus the project's
`AGENTS.md` only with `--trust-project`) load through Deep Agents `MemoryMiddleware` with a
read-only allowlisted
backend. They are read once per run into private graph state and appended to every model call's
system message, so they never enter JSONL history and compaction/summaries cannot drop them;
the harness system footprint recorded for capacity includes them.

Long-term memory uses LangMem directly; pycode owns only storage location, trigger timing,
viewing/editing and undo. `memory/services/memory.py` (MemoryService) recalls entries before a task
(all of them up to 20, otherwise a metered selector picks up to 10), schedules one background
`create_memory_store_manager` pass when a run completes or after manual/automatic compaction, logs every
change and undoes the latest automatic pass. The model also gets LangMem
`manage_*_memory`/`search_*_memory` tools through ordinary settlement and MemoryToolGate.
Background memory models always run with thinking disabled (forced tool choice conflicts with
DeepSeek thinking); failures are logged, never retried and never reach the conversation.
Automatic passes write the user scope and, for a trusted project, the project scope. The write
filter (`memory/domain/memory.py`: length, per-scope count, credential patterns) applies to every write.
JsonMemoryStore keeps one atomic `memory.json` per scope with prefix and simple text search.

GC marks all durable references and
live/uncertain writers before 30-day quarantine/purge; memory directories are explicitly
excluded from managed objects. Worktree integration/cleanup stays separate.
Seatbelt profiles deny by default: reads cover the workspace, system directories, temp,
the running interpreter's prefixes/import roots and git global config; later rules deny
~/.ssh, ~/.aws, ~/.gnupg, ~/.config, ~/.pycode and every .env/.env.* except a workspace
.env.example. Writes stay in the workspace outside .git/.pycode and dotenv files (so they cannot
be renamed out of the read deny). Toolchains and caches under HOME are not readable.
`on-auto` approves only commands whose every segment passes the pure sandbox allow list
(`sandbox/domain/command_policy.auto_approvable`, quote-aware; unquoted globs in find/rg/git arguments are
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

## Ports and concrete implementations

Every Protocol declared in feature ports and shared contracts is listed below. Multiple
implementations may exist (for example, ToolGate and SandboxBackend); one concrete production
implementation is shown per port. Factory and callback rows identify the composition binding,
not the Protocol itself. `docs/design/port-implementations.json` records exact source symbols;
the architecture check verifies complete coverage and rejects missing or Protocol-only bindings.
Private adapter-local SDK Protocols are implemented by injected PycodeBackend/framework objects.

| Consumer port | Production implementation |
| --- | --- |
| `agent.InputQueue` | `session.services.input_queue.PendingInputs` |
| `agent.DurableInputDelivery` | `session.services.durable_inputs.DurableInputs` |
| `agent.HistoryProjector` | `context.services.context_projector.ContextProjector` |
| `agent.RequestTracking` | `models.adapters.model_requests.ModelRequestRunner` |
| `agent.TeamMailbox` | `team.services.team.TeamMember` |
| `agent.TitleAssignment` | `app.composition.runtime.BoundTitleAssignment` |
| `agent.WorkspaceReadback` | `tools.adapters.backend.PycodeBackend` |
| `agent.FallbackControl` | `models.adapters.model_fallback.ModelFallback` |
| `agent.SelectorSettingsView` | `models.adapters.tool_selection.ToolSelector` |
| `context.SummaryGenerator` | `app.composition.context.ConfiguredSummaryGenerator` |
| `context.SummaryStore` | `context.adapters.storage.summaries.FileSummaryStore` |
| `hooks.HookActions` | `hooks.adapters.actions.ConfiguredHookActions` |
| `hooks.LifecycleHooks` | `hooks.services.hooks.HookEngine` |
| `hooks.LifecycleContext` | `agent.services.context.RunContext` |
| `hooks.HookExecution` | `tools.services.tool_executor.ToolExecutor` |
| `hooks.HookPlanGuard` | `plans.services.plans.PlanService` |
| `hooks.HookExecutorFactory` | `app.composition.runtime.build_hook_engine` (factory/callback) |
| `kernel.MessageJournal` | `session.adapters.storage.session.JsonlSessionStore` |
| `kernel.HistoryReader` | `session.adapters.storage.session.JsonlSessionStore` |
| `kernel.HistoryJournal` | `session.adapters.storage.session.JsonlSessionStore` |
| `kernel.ExecutableTool` | `sandbox.adapters.tools.bash.Bash` |
| `kernel.EventConsumer` | `ui.adapters.console.ConsoleOutput` |
| `kernel.EventSink` | `kernel.services.events.EventStream` |
| `kernel.EventJournal` | `kernel.services.event_journal.MemoryEventJournal` |
| `kernel.Timing` | `kernel.io.timing.SystemTiming` |
| `mcp.MCPConnections` | `mcp.adapters.manager.MCPAdapterConnections` |
| `mcp.SchemaChecker` | `tools.adapters.schema.JsonSchemaValidator` |
| `memory.MemoryUpdater` | `app.composition.memory.ConfiguredMemoryUpdater` |
| `models.Projector` | `context.services.context_projector.ContextProjector` |
| `models.RunOwnership` | `session.domain.guards.SessionRunOwnership` |
| `models.RegistryLookup` | `tools.services.tool_registry.ToolRegistry` |
| `models.ResourceLookup` | `skills.services.skill_resources.SkillResources` |
| `models.CalibrationReport` | `context.domain.summaries.SummaryReport` |
| `models.SummaryBudget` | `context.services.summary.SummaryService` |
| `models.CalibrationContext` | `agent.services.context.RunContext` |
| `models.ModelFailureClassifier` | `models.adapters.failures.ProviderFailureClassifier` |
| `models.RequestStore` | `models.adapters.storage.requests.SQLiteRequestStore` |
| `models.ModelObservation` | `models.adapters.observation.ProviderObservation` |
| `models.ModelDirectory` | `models.adapters.directory.SDKModelDirectory` |
| `models.CatalogStore` | `models.adapters.storage.catalog.FileCatalogStore` |
| `plans.PlanFiles` | `plans.adapters.storage.plans.FilePlanStore` |
| `plans.QuestionPrompt` | `plans.adapters.plans.InterruptPlanPrompt` |
| `sandbox.StreamWriter` | `tools.adapters.output_store.StreamingOutputWriter` |
| `sandbox.OutputSink` | `tools.adapters.output_store.FileToolOutputStore` |
| `sandbox.ProcessRunner` | `sandbox.adapters.tools.process_runner.run_process` |
| `sandbox.SandboxBackend` | `sandbox.adapters.seatbelt.RequiredSandbox` |
| `session.SessionStore` | `session.adapters.storage.session.JsonlSessionStore` |
| `session.RuntimeLauncher` | `app.runtime_launcher.ConfiguredRuntimeLauncher` |
| `session.SessionRepository` | `session.adapters.storage.session_repository.JsonlSessionRepository` |
| `session.StorageObjects` | `session.adapters.storage.objects.FileStorageObjects` |
| `session.WorkspaceInspector` | `session.adapters.storage.workspace.FileWorkspaceInspector` |
| `skills.CatalogWriteGuard` | `session.domain.guards.SessionCatalogWriteGuard` |
| `skills.SchemaChecker` | `tools.adapters.schema.JsonSchemaValidator` |
| `skills.SkillReadGrant` | `app.composition.capabilities.skill_read_grant` |
| `skills.SkillCatalog` | `skills.adapters.catalog.FileSkillCatalog` |
| `skills.SkillResourceToolFactory` | `skills.adapters.python_tool.PythonSkillToolFactory` (factory/callback) |
| `skills.SkillCatalogCommands` | `skills.services.skills.SkillService` |
| `skills.SkillInstaller` | `skills.adapters.install.GitHubSkillInstaller` |
| `snapshot.SnapshotStore` | `snapshot.adapters.git_tree.GitSnapshotStore` |
| `team.ChildSession` | `session.services.agent_session.AgentSession` |
| `team.ChildSessions` | `app.composition.team.ConfiguredChildSessions` |
| `team.Worktrees` | `team.adapters.worktrees.WorktreeManager` |
| `team.WorktreeSnapshot` | `snapshot.adapters.git_tree.GitSnapshotStore` |
| `team.WorktreeSnapshotFactory` | `snapshot.adapters.git_tree.GitSnapshotStore` (factory/callback) |
| `tools.ToolDispatch` | `mcp.adapters.tools.MCPCall` |
| `tools.ToolHooks` | `hooks.services.tool_bridge.ToolHookBridge` |
| `tools.OutputWriter` | `tools.adapters.output_store.StreamingOutputWriter` |
| `tools.ToolOutputStore` | `tools.adapters.output_store.FileToolOutputStore` |
| `tools.SessionHeaderReader` | `session.adapters.storage.header_reader.FileSessionHeaderReader` |
| `tools.MessagePublisher` | `agent.adapters.boundaries.AgentMessagePublisher` |
| `tools.ToolLifecycle` | `app.composition.runtime.build_runtime_bindings` (factory/callback) |
| `tools.QuestionPrompt` | `tools.adapters.questions.InterruptQuestionPrompt` |
| `tools.ArgumentValidator` | `tools.adapters.schema.JsonSchemaValidator` |
| `tools.ToolGate` | `tools.services.permissions.ReadOnlyToolGate` |
| `ui.InteractiveSession` | `session.services.agent_session.AgentSession` |
| `ui.InteractiveSessions` | `app.composition.ui.ConfiguredInteractiveSessions` |
