# Python Skill resource-tool development evidence

Resource declarations and implementations now use the common ToolRegistry,
ToolExecutor, permissions, operations, output, snapshot, and Graph recovery path.
The focused `skill-resources-tests.xml` report has 24 passing cases. Full regression
passed 525 cases with one Windows file-symlink-privilege skip (`latest-tests.xml`).
The subsequent file-argument ownership guard and its two workspace-scope cases are
covered by the latest focused report. Hooks and Worker fork remain outside this acceptance.

Source vectors were recorded in `tests/fixtures/skill-tools-baselines.json` before
implementation, from the fixed local `skills/directory.py` and execution-route
tests. Adaptations retain `tool.json`, `parameters`/`input_schema`, named Python
files, and `execute(**arguments)`; registration inspects bytes instead of importing
resource code inside the application process.

Verified behavior:

- Catalog discovery hashes resource declarations and Python implementation files
  without importing them. LoadSkill validates JSON schemas, required implementation
  paths, and name conflicts before appending an activation. The active record stores
  exact declarations, schema/implementation hashes and the aggregate resource hash.
  Explicit reload detects implementation changes even when SKILL.md is unchanged.
- Resource tools default to external effects and sequential execution. Their schema
  reaches the next actual SDK request through the public ModelRequest override.
  Parent permissions still apply; invalid arguments and denials do not import code.
  Nonlocal schema references are rejected instead of performing implicit network I/O.
- Sync and async resource calls execute in an owned Python process. Import side
  effects begin after approval. The worker verifies implementation bytes before
  compiling, and exposes failure as an error result. Common SandboxBackend wraps
  the launch; macOS physical sandbox enforcement remains a final host gate.
- Actual close/reopen preserves the approval and operation ID. Changed schemas,
  missing modules, or changed implementation files fail before executing or sending
  a replacement model request. Restoring exact bytes resumes the original call.
- Real parent exits at output publication and result-ready settlement reuse confirmed
  output/results. A persistent resource effect counter stays at one after recovery.
- Timeout and cancellation kill the worker and a real spawned child on this Windows
  host. Late child effects do not occur. Unicode output above the preview line budget
  remains complete on disk and is readable through the scoped output store.
- Declared file_path/resume_path arguments preserve the source workspace checks;
  permitted paths are normalized, escaped paths fail before importing, and the
  asynchronous filesystem guard collects its owned thread on cancellation.
- Actual compaction preserves dynamic tool declarations and loaded Skill snapshots.
  Reopen reconstructs the current branch's tool set; branching before activation
  removes those schemas. Runtime and credential-free views report the same context
  usage. Textual `/skill:resource` shows ordinary approval before executing Python.

`tool_set_change` records describe the resource subset; JSONL activation snapshots
remain authoritative for branch restoration. The base execution fingerprint stays
stable across intentional in-run activation, while resource hashes and operation
spec hashes verify dynamic implementations on recovery. The framework graph uses
public middleware and dict schemas; it does not inspect private framework registries.

Hooks and AgentTeam fork integration are pending, so checklist items that combine
those behaviors remain unchecked. No development Git or final history integration
has been created, and the fixed reference sources are unchanged.
