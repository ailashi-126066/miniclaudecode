# MiniCode

MiniCode is a Java 21 full-screen coding agent based on the MewCode teaching project. It keeps
MewCode's readable virtual-thread/`BlockingQueue` pipeline and adds a LangChain4j provider layer,
hybrid code retrieval, SQLite memory, structured plans, an execution ledger, and diff-bound file
approval.

## Architecture

```text
JLine/Mordant TUI | Print | Javalin Remote
                    |
                  Agent
          +---------+---------+
          |                   |
 LangChain4j -> BlockingQueue  ToolRegistry
                              |- deferred CodeSearch
                              |- Plan tools
                              `- ledgered file/command tools

Lucene BM25 + vector + RRF     SQLite FTS5 memory
```

LangChain4j is used only for model transport. Agent orchestration remains the explicit local loop;
LangGraph4j is not used.

## Build

```powershell
.\mvnw.cmd clean verify
java -jar target\minicode.jar --help
```

## Code RAG

```powershell
java -jar target\minicode.jar index -w .
java -jar target\minicode.jar rag stats -w .
java -jar target\minicode.jar rag explain "where are tool permissions checked" -w .
```

Java is chunked with JavaParser; supported non-Java languages use Tree-sitter. Retrieval combines
Lucene BM25 and deterministic vector embeddings through reciprocal-rank fusion. No benchmark number
is claimed until a project-specific labelled dataset is run.

## Persistence

- User memory: `~/.mewcode/memory.db`
- Project memory: `.mewcode/memory.db`
- Structured plan: `.mewcode/plans/active.json`
- Tool ledger: `.mewcode/sessions/<session>/tool-ledger.jsonl`

The legacy `.mewcode` directory and `com.mewcode` package remain compatible. Original MewCode source
attribution is retained in inherited files; the Maven, LangChain4j, RAG, SQLite, plan, ledger, and
diff/hash work is the MiniCode extension layer.
