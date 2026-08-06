# MiniClaudeCode RAG benchmark v2

This benchmark contains 52 hand-labelled queries, split evenly across English, Chinese, qualified
symbol lookup, and paraphrased natural-language intent. Each case lists several acceptable chunks
and one canonical implementation method.

- `recall@K`: fraction of queries with any acceptable chunk in the first K results.
- `canonical@K`: fraction with the preferred implementation method in the first K results.
- `MRR`: reciprocal rank of the first acceptable chunk.

The top-level `benchmarks` directory is deliberately excluded by `WorkspaceScanner`, so evaluation
queries cannot retrieve themselves.

## Baseline measured on 2026-08-06

Persistent indexes were synchronized immediately before evaluation. Latency is omitted from the
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

Choose a configured isolated user home and run each split independently:

```powershell
$benchmark = "benchmarks\rag\miniclaudecode-v2"
java "-Duser.home=$PWD\target\rag-onnx-benchmark-home" `
  -jar agent-cli\target\mini-claude-code.jar rag eval "$benchmark\english.jsonl"
```

Repeat with `chinese.jsonl`, `symbol.jsonl`, and `natural-language.jsonl`. A one-point change in a
13-query split moves its rate by 0.077, so compare both the grouped and 52-query aggregate results.
