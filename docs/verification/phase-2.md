# Phase 2 implementation evidence — 2026-10-10

Current full suite: 207 passed. Ruff, architecture, source baseline and dependency
consistency checks pass. The development gate now includes user-facing model
commands and durable model_change / branch restoration. JSONL storage foundation
is delivered and undergoing the remaining phase-3 output management work.
No final Git history integration was attempted.

Implemented and verified:

- Public LangChain provider integrations through the common create_agent /
  LangGraph runtime: DeepSeek, OpenAI Chat Completions, OpenAI Responses and
  Anthropic Messages. Real loopback HTTP requests exercise native SDK encoding,
  SSE decoding, thinking/signatures, tool results and the next request.
- Each model attempt starts a durable SQLite record before invoking the handler.
  Successful, failed and cancelled attempts settle separately. Stable request IDs,
  origin purpose, retry purpose, run/session/agent identifiers and raw usage are
  retained. Closing and reopening the database preserves attempts and usage.
- At most three retries, capped exponential backoff and Retry-After in seconds or
  HTTP-date form. Deterministic HTTP errors stop; unknown implementation defects
  are recorded then re-raised. Retrying a model cannot re-execute completed tools.
- A valid-looking tool call from a stream that ended without a terminal marker is
  discarded. The request can retry with unchanged completed history; no partial
  tool effect or completed assistant artifact is invented.
- Model discovery uses public SDK models.list and pagination. Catalog publication
  is atomic, sorted and scoped by provider configuration hash. Partial capability
  overrides preserve other values. Unknown discovered models remain selectable
  with undeclared capabilities disabled. Cache reload exposes unknown/stale
  availability; an unavailable model raises an explicit selection error.
- Actual wire tests exposed missing DeepSeek cache miss / reasoning replay and
  Anthropic input/cache usage. The SDK observation adapter preserves decoded
  source usage without writing HTTP, SSE or provider message parsers. Missing
  token fields remain null even when the integration fabricated a default zero.

Remote service acceptance is separate from local protocol fixtures:

- `live-deepseek.json`: deepseek-chat was not in the authoritative server catalog;
  no request was silently redirected to another model.
- `live-deepseek-flash-off.json`: explicit deepseek-flash, thinking disabled,
  native model → read-only AcceptanceProbe → model completed in two requests.
- `live-deepseek-flash-thinking.json`: explicit thinking enabled, same two-request
  tool loop completed with provider reasoning/cache usage preserved.
- `live-openai.json` and `live-anthropic.json`: unverified because their credentials
  are not configured. Local real HTTP protocol checks do not claim those remote
  services passed.

Acceptance credentials were read by environment-variable reference from the
project's existing environment file. Reports contain neither keys nor request
authentication headers. Transient acceptance databases live in ignored
`.acceptance/`; reports retain only verification outcomes, request IDs and usage.

Additional phase-2 acceptance:

- `model` and `/model` list all configured providers, refresh the public SDK
  directory, expose current branch selection and append a selection change to an
  existing session. `--cached` is explicit; unavailable models produce errors.
- Model/thinking/capability snapshots persist without keys, URLs or copied clients.
  Provider configuration fingerprints stop incompatible restoration; an explicit
  reselection publishes the new fingerprint. Thinking parameters and effort are
  retained and restored.
- Real HTTP execution switches DeepSeek → OpenAI, closes/reopens the history, then
  returns to a prior branch and continues with DeepSeek. The abandoned branch's
  input is absent from the subsequent provider request.
- CLI real DeepSeek directory output is retained in `model-cli-catalog.json`.
  Credential references resolve from the process environment first, with only the
  requested references read from the project's environment file as a fallback.

Known future gates: tool output storage/readback, budget cleanup, checkpoint
recovery across commit gaps, approvals, summaries, snapshots, complete CLI/TUI and
all extension modules. Official OpenAI/Anthropic remote account acceptance remains
unverified. SQLite model attempt records do not replace completed JSONL history or
prove execution recovery is finished.
