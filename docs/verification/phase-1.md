# Phase 1 verification — 2026-10-10

Current full suite: `python -m pytest -q` reports 100 passed; `ruff check .` passes.
This count includes the completed phase-0 checks and the model configuration / type
construction work already underway for phase 2. It does not represent 100 live
service tests.

The upstream Agent test vectors were extracted before their Python implementations.
`tests/fixtures/pi-agent-loop.json` records the fixed commit, source file SHA-256,
original test names and expectations. Implementation source headers retain lineage.

Verified with actual LangChain `create_agent` and compiled LangGraph execution,
using a scripted `BaseChatModel` through its streaming interface:

- User → completed assistant → tool result → next assistant order, including
  complete model persistence before any tool starts.
- Agent/turn/message lifecycle events and streaming deltas before completion.
- Partially streamed text is visible, but cancellation leaves only completed
  history and waits for stream cleanup.
- Valid but length-truncated tool arguments never execute; paired failure results
  reach the next model request. Length-limited text does not report success.
- Concurrent tool completion events retain completion order, while history and
  the next model request retain original call order.
- A sequential tool forces a barrier even under the parallel default.
- Steering enters only after all tool results settle. Follow-up input waits until
  current work would end. Stable input IDs reject content or queue-kind changes.
- Cancellation propagates through the graph's tool tasks and cleanup completes
  before the runtime returns.

Additional contract checks cover JSON schema validation, unknown tools, permission
denials, tool I/O errors, uncaught implementation defects, immutable registered
declarations and isolation of tool argument mutation from recorded calls.

Provider message conversion retains DeepSeek reasoning, Anthropic thinking
signatures/redacted content and OpenAI Responses reasoning summaries. Usage
preserves source metadata and distinguishes unreported token fields from reported
zero. No model HTTP implementation was copied or replaced.

The journal used in phase-1 framework tests is an observable test boundary.
Durable JSONL persistence belongs to phase 3; restart/checkpoint recovery belongs
to phase 4. The current default gate rejects modifications requiring approval;
durable interactive approval is not yet implemented. The production CLI currently
provides diagnostics, with the full execution CLI scheduled for phase 7.

Phase 2 remains open: model directory refresh, capability merging, retry accounting,
failure classification, real wire protocol tests and real service acceptance still
require implementation and verification. Model client construction with fake keys
does not satisfy those gates.
