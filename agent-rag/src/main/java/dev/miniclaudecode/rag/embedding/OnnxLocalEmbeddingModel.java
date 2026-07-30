package dev.miniclaudecode.rag.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.util.Objects;

/**
 * Neural embedding model backed by an ONNX-bundled MiniLM-L6-v2.
 *
 * <p>Unlike the hashing-based {@link LocalCodeEmbeddingModel}, this produces real semantic
 * embeddings that capture synonymy and paraphrase relationships. The ONNX model is bundled inside
 * the JAR (via {@code langchain4j-embeddings-all-minilm-l6-v2}), so it works offline without any
 * external service. Output dimension is fixed at 384 to match the upstream model.
 */
public final class OnnxLocalEmbeddingModel implements EmbeddingModel, EmbeddingIdentity {
  public static final int DIMENSIONS = 384;
  private final AllMiniLmL6V2EmbeddingModel delegate;

  public OnnxLocalEmbeddingModel() {
    this.delegate = new AllMiniLmL6V2EmbeddingModel();
  }

  @Override
  public Response<Embedding> embed(String text) {
    return this.delegate.embed(Objects.requireNonNullElse(text, ""));
  }

  @Override
  public int dimension() {
    return DIMENSIONS;
  }

  @Override
  public String embeddingIdentity() {
    return "onnx-minilm/" + DIMENSIONS;
  }
}
