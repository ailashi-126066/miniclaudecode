# MiniClaudeCode RAG benchmark v2

This benchmark contains 52 hand-labelled queries, split evenly across English, Chinese, qualified
symbol lookup, and paraphrased natural-language intent. Each case lists several acceptable chunks
and one canonical implementation method.

- `recall@K`: fraction of queries with any acceptable chunk in the first K results.
- `canonical@K`: fraction with the preferred implementation method in the first K results.
- `MRR`: reciprocal rank of the first acceptable chunk.

The top-level `benchmarks` directory is deliberately excluded by `WorkspaceScanner`, so evaluation
queries cannot retrieve themselves.

## What the Chinese split actually measures

The Chinese queries ask about this repository's **Java source**, whose identifiers, comments and
Javadoc are English. That makes the split a cross-lingual retrieval task, and BM25 cannot serve it
at any tokenization: there are no shared terms between 在每轮代理执行前创建 Git 检查点 and
`GitCheckpointService.create(long)` beyond the word `Git`. Its score is therefore a measurement of
the embedding model's multilingual ability, not of the lexical pipeline — improving it means a
multilingual embedding model, not a better analyzer.

Chinese retrieval over a Chinese *corpus* is a different question and is not covered here. It is
what the CJK bigram analyzer actually improves, and it is worth a fifth split — but the split needs
a Chinese corpus inside the indexed tree to measure against, and this repository no longer ships
one.

## Historical baseline measured on 2026-08-06

The ONNX rows below are retained as historical measurements only. The current production wiring
does **not** contain or select an ONNX embedding backend: `auto` chooses the configured remote
OpenAI-compatible embedding endpoint, otherwise it falls back to deterministic `fast` hashing.
Therefore these ONNX numbers cannot be reproduced by the current fat JAR and must not be presented
as its production baseline.

At the time of the historical run, persistent indexes were synchronized immediately before
evaluation. Latency is omitted from the
baseline because 13-query percentiles are dominated by JVM/model warm-up and OS cache state.

| Provider | Strategy | Recall@5 | Recall@10 | Canonical@5 | Canonical@10 | MRR |
|---|---|---:|---:|---:|---:|---:|
| fast | BM25 | 0.250 | 0.288 | 0.154 | 0.173 | 0.163 |
| fast | vector | 0.308 | 0.404 | 0.173 | 0.231 | 0.198 |
| fast | hybrid | 0.519 | 0.538 | 0.404 | 0.404 | 0.466 |
| ONNX | BM25 | 0.250 | 0.288 | 0.154 | 0.173 | 0.163 |
| ONNX | vector | 0.442 | 0.558 | 0.327 | 0.500 | 0.362 |
| ONNX | hybrid | **0.558** | **0.558** | **0.423** | **0.423** | **0.501** |

ONNX hybrid results by group:

| Group | Recall@5 | Recall@10 | Canonical@5 | Canonical@10 | MRR |
|---|---:|---:|---:|---:|---:|
| English | 0.923 | 0.923 | 0.615 | 0.615 | 0.808 |
| Chinese | 0.077 | 0.077 | 0.077 | 0.077 | 0.015 |
| Symbol | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| Natural language | 0.231 | 0.231 | 0.000 | 0.000 | 0.179 |

## Run

To produce a current, reproducible baseline, choose an isolated user home, configure either
`rag.embedding.provider: remote` (with `base-url`, `model`, dimensions and key) or `fast`, rebuild
the index, and run each split independently. Label the results with that configured provider; do
not label them ONNX.

```powershell
$benchmark = "benchmarks\rag\miniclaudecode-v2"
java "-Duser.home=$PWD\target\rag-benchmark-home" `
  -jar agent-cli\target\mini-claude-code.jar rag eval "$benchmark\english.jsonl"
```

Repeat with `chinese.jsonl`, `symbol.jsonl`, and `natural-language.jsonl`. A one-point change in a
13-query split moves its rate by 0.077, so compare both the grouped and 52-query aggregate results.

Synchronize the index first (`... index -w .`): `rag eval` queries whatever is on disk and does not
build it. Evaluating a stale index is not an error, it is a low score.

Ground truth is content-addressed: each `chunkId` hashes path, kind, owner, symbol and start line.
Editing the indexed source moves start lines and silently invalidates the affected cases, so
re-verify the IDs after touching the files these queries target.
