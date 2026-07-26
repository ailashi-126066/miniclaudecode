# RAG evaluation

## 检索链

1. JavaParser AST/结构化文本产生稳定 chunk id 和符号元数据。
2. Lucene BM25 对内容、符号和路径字段检索，符号权重最高。
3. 离线 384 维 signed feature hashing 对 camelCase、snake_case、路径 token 和字符 trigram 建向量；它可重复、无网络依赖，适合工程演示，但不是神经语义模型。
4. 两路各取候选后用 Reciprocal Rank Fusion 合并，默认 `k=60`，再执行 top-k 与 token budget。

离线向量路由的优势是可复现与零模型下载，局限是跨语义同义词能力弱。生产扩展点是替换 LangChain4j `EmbeddingModel`，Lucene/RRF 其余链路无需变化。

## 命令

```bash
miniclaude index -w /path/to/project
miniclaude rag -w /path/to/project explain "approval checkpoint"
miniclaude rag -w /path/to/project eval agent-rag/src/test/resources/eval/java-fixture.jsonl
```

评测 JSONL 每行包含：

```json
{"query":"find order by id","relevantChunkIds":["expected-stable-chunk-id"]}
```

输出 BM25、Vector、Hybrid 三路的 Recall@5、Recall@10、MRR、P50 和 P95，应保存同一数据集上的对比，而不是只展示单个漂亮查询。`rag explain` 给出两路候选数量和最终 rank，便于分析失败案例。

## 面试可讲的取舍

RRF 只依赖排名，不直接比较 BM25 分数和向量相似度；这避免手工归一化的不稳定。增量索引用内容指纹跳过未变文件，Lucene commit 成功后才更新 fingerprint store，防止崩溃后误认为索引已完成。
