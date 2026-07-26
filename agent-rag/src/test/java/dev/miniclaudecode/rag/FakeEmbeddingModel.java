package dev.miniclaudecode.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.nio.charset.StandardCharsets;

public final class FakeEmbeddingModel implements EmbeddingModel {

  public static final int DIMENSIONS = 8;

  @Override
  public Response<Embedding> embed(String text) {
    float[] vector = new float[DIMENSIONS];
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    for (int index = 0; index < bytes.length; index++) {
      vector[index % vector.length] += (bytes[index] & 0xff) / 255.0f;
    }
    if (bytes.length == 0) {
      vector[0] = 1;
    }
    return Response.from(Embedding.from(vector));
  }

  @Override
  public int dimension() {
    return DIMENSIONS;
  }
}
