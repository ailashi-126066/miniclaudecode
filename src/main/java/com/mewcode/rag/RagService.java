package com.mewcode.rag;

import com.mewcode.rag.embedding.LocalCodeEmbeddingModel;
import com.mewcode.rag.index.LuceneCodeIndex;
import com.mewcode.rag.search.Bm25Retriever;
import com.mewcode.rag.search.HybridCodeSearcher;
import com.mewcode.rag.search.VectorRetriever;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Workspace-scoped facade for indexing, hybrid retrieval and diagnostics. */
public final class RagService {
    private final Path workspace;
    private final Path indexRoot;
    private final LocalCodeEmbeddingModel embeddings;

    public RagService(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.indexRoot = this.workspace.resolve(".mewcode/rag-index");
        this.embeddings = new LocalCodeEmbeddingModel();
    }

    public LuceneCodeIndex.UpdateReport synchronize() throws IOException {
        return new LuceneCodeIndex(indexRoot, embeddings).synchronize(workspace);
    }

    public LuceneCodeIndex.IndexStats stats() throws IOException {
        return new LuceneCodeIndex(indexRoot, embeddings).stats();
    }

    public HybridCodeSearcher.SearchResponse search(String query) throws IOException {
        LuceneCodeIndex index = new LuceneCodeIndex(indexRoot, embeddings);
        HybridCodeSearcher searcher = new HybridCodeSearcher(
                new Bm25Retriever(index.luceneDirectory()),
                new VectorRetriever(index.luceneDirectory(), embeddings));
        return index.hydrateParentContext(searcher.search(query), 6000);
    }

    public String explain(String query) throws IOException { return search(query).explain(); }
    public com.mewcode.rag.eval.RagEvaluator.EvaluationReport evaluate(Path dataset) throws IOException {
        var evaluator = new com.mewcode.rag.eval.RagEvaluator();
        return evaluator.evaluate(evaluator.load(dataset), Map.of("hybrid", query -> search(query).results()));
    }
    public Path workspace() { return workspace; }
}
