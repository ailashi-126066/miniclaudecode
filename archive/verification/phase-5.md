# Phase 5 development verification — 2026-10-11

Development acceptance passed with 340 tests. Ruff, architecture/branding, unchanged
source baselines, dependency consistency and wheel/sdist builds pass. The background
summary chain is now wired into the shared application/runtime. Actual remote
provider accounts, full CLI/TUI and the later snapshot/extension gates are separate.

Source hashes and initial vectors were recorded before implementation in
`tests/fixtures/compaction-baselines.json`. Pi's fixed compaction tests cover custom
messages, split-turn boundaries and oversized trailing tool results. The local
summary-cache tests cover the 85% trigger and output reserve causing an earlier
trigger. Source/author notices remain in the policy modules.

`core/context_budget.py` faithfully ports separate input-limit/cache-trigger
thresholds with explicit configuration. `core/compaction.py` ports Pi's backwards
ceil/UTF-16 scan and nearest legal message candidate, including turn-prefix location
and adjacent context-free metadata. These use canonical projected entries; pairing
is validated against the native branch before publishing compaction. Split turns
retain their original user/task prefix in a dedicated serialized summary section.

Verified implementation:

- Immutable source/result envelopes record job, session, source leaf/entry IDs,
  first kept entry, previous compaction, prefix/config hashes, model, prompt/tool
  checkpoint, usage and status. Atomic publication is flushed/fsynced and checked
  on load. Invalidation writes a tombstone without deleting source, result or output.
- The launcher owns a SummaryService across user tasks. Production uses an owned
  asyncio task with a clean contextvars context, plus an independent public
  LangChain create_agent graph with checkpoint inheritance explicitly disabled.
  Its model factory/clients/request store close before production returns.
- Real native SDK/loopback HTTP tests hold the summary response while the current
  user task completes. At input capacity, the next model waits and then sends the
  summary plus retained raw messages and newly appended input, without old prefix.
- Source validation permits a new tail but rejects changed prefix, branch boundary,
  previous compaction, model/config, prompt or tool declarations. Running stale jobs
  are cancelled and collected. Ready invalid objects remain on disk for references.
- Safe adoption appends a compaction entry, preserving raw JSONL. A dry candidate
  projection must fit the reserved input limit; otherwise no compaction is appended.
  Checkpoints restore the full system prompt and tool declarations. Repeated
  compaction and branch navigation produce exactly the relevant latest summary.
- Model-derived usage includes cache reads once, then estimates new tail content.
  Context edits/compaction or configuration changes invalidate stale usage anchors.
  Original usage is preserved in JSONL/SQLite and never replaced by estimates.
- Summary requests use purpose=summary; real retries remain purpose=retry with
  origin metadata and independent attempts. Both summary and agent use official
  SDK/framework requests and the shared accounting path, without tool execution.
  Empty/tag-only/analysis-only, length-truncated or tool-calling summaries are rejected.
- OpenAI and Anthropic native overflow signals are classified without recording
  raw error bodies. A main overflow can force summary adoption and resend through
  the same public framework handler; the failed request and resend are metered.
- Actual child processes exit while generating, after a summary request started,
  and after result publication. Completed objects are restored; lost workers are
  regenerated and started attempts marked interrupted. No process-local socket or
  coroutine is presented as surviving restart.
- Close/cancel collects background work. Finished valid objects can be restored;
  interrupted objects cannot masquerade as generating. Prefixes awaiting summary
  are protected from automatic eviction; original output IDs remain readable after
  eviction and adoption. Garbage collection and its 30-day quarantine remain later.

Application service: `AgentSession.compact()`; automatic prepare/adoption occurs at
settled model/tool boundaries. CLI `/compact` and its UI are delivered in phase 7.
