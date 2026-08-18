# MiniCode extension guide

## Model streaming

`LangChainClient` translates MewCode messages and schemas to LangChain4j, then translates callbacks
back into the existing bounded `BlockingQueue<StreamEvent>`. This preserves the producer-consumer
model documented by MewCode while adding Anthropic, OpenAI-compatible, and Ollama behind one adapter.

## Hybrid retrieval

`RagService` owns workspace indexing and search. `FallbackChunker` selects JavaParser, Tree-sitter,
symbol, or text chunking. `HybridCodeSearcher` combines BM25 and vector candidates with RRF and code
feature reranking. `CodeSearchTool` is deferred and appears after ToolSearch discovery.

## Memory and plans

`SqliteMemoryStore` keeps active and superseded records and exposes FTS5 retrieval. `PlanCoordinator`
persists a structured state alongside a human-readable Markdown plan and refuses to complete steps
that require verification without evidence.

## Side effects

`StreamingExecutor` hashes canonical tool arguments and persists side-effect transitions. File writes
are previewed as unified diffs; approval is bound to the pre-image hash and diff hash. The target is
re-read before execution, and writes use an atomic replace. A stale PENDING record becomes UNKNOWN on
the next run and is not automatically replayed.
