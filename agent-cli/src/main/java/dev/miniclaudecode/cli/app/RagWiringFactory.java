package dev.miniclaudecode.cli.app;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.miniclaudecode.persistence.config.EmbeddingConfig;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import dev.miniclaudecode.rag.embedding.LocalCodeEmbeddingModel;
import dev.miniclaudecode.rag.embedding.OnnxLocalEmbeddingModel;
import dev.miniclaudecode.rag.embedding.RemoteEmbeddingModel;
import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import dev.miniclaudecode.rag.search.Bm25Retriever;
import dev.miniclaudecode.rag.search.HybridCodeSearcher;
import dev.miniclaudecode.rag.search.VectorRetriever;
import java.nio.file.Path;
import java.util.Map;

/** Builds the embedding provider, persistent Lucene index, and hybrid retrieval pipeline. */
final class RagWiringFactory {
  private RagWiringFactory() {}

  static Wiring create(
      Path workspace,
      UserDataLayout layout,
      EmbeddingConfig embedding,
      Map<String, String> environment) {
    EmbeddingModel embeddings = embeddingModel(embedding, environment);
    LuceneCodeIndex codeIndex =
        new LuceneCodeIndex(layout.indexWorkspaceRoot(workspace), embeddings);
    Bm25Retriever bm25 = new Bm25Retriever(codeIndex.luceneDirectory());
    VectorRetriever vector = new VectorRetriever(codeIndex.luceneDirectory(), embeddings);
    return new Wiring(codeIndex, bm25, vector, new HybridCodeSearcher(bm25, vector));
  }

  private static EmbeddingModel embeddingModel(
      EmbeddingConfig embedding, Map<String, String> environment) {
    return switch (embedding.provider()) {
      case AUTO ->
          embedding.baseUrl().isPresent()
              ? new RemoteEmbeddingModel(
                  embedding.baseUrl().orElseThrow(),
                  embedding.resolvedApiKey(environment),
                  embedding.model(),
                  embedding.dimensions(),
                  embedding.timeout())
              : tryOnnxOrFallback(embedding.dimensions());
      case ONNX -> new OnnxLocalEmbeddingModel();
      case FAST -> new LocalCodeEmbeddingModel(embedding.dimensions());
      case REMOTE ->
          new RemoteEmbeddingModel(
              embedding
                  .baseUrl()
                  .orElseThrow(
                      () ->
                          new IllegalStateException("remote embedding provider requires base-url")),
              embedding.resolvedApiKey(environment),
              embedding.model(),
              embedding.dimensions(),
              embedding.timeout());
    };
  }

  private static EmbeddingModel tryOnnxOrFallback(int dimensions) {
    try {
      if (dimensions == OnnxLocalEmbeddingModel.DIMENSIONS) {
        return new OnnxLocalEmbeddingModel();
      }
    } catch (Exception | LinkageError ignored) {
      // Keep startup available when native ONNX support cannot initialize.
    }
    return new LocalCodeEmbeddingModel(dimensions);
  }

  record Wiring(
      LuceneCodeIndex codeIndex,
      Bm25Retriever bm25,
      VectorRetriever vector,
      HybridCodeSearcher searcher) {}
}
