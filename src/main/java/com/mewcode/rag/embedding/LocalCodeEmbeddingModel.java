package com.mewcode.rag.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class LocalCodeEmbeddingModel implements EmbeddingModel, EmbeddingIdentity {
  public static final int DEFAULT_DIMENSIONS = 384;
  private final int dimensions;

  public LocalCodeEmbeddingModel() {
    this(384);
  }

  public LocalCodeEmbeddingModel(int dimensions) {
    if (dimensions < 32) {
      throw new IllegalArgumentException("embedding dimensions must be at least 32");
    } else {
      this.dimensions = dimensions;
    }
  }

  public Response<Embedding> embed(String text) {
    String normalized = text == null ? "" : splitIdentifiers(text);
    float[] vector = new float[this.dimensions];

    for (String token : normalized.split("[^\\p{L}\\p{N}_.-]+")) {
      if (!token.isBlank()) {
        addFeature(vector, token, 1.0F);
        if (token.length() >= 3) {
          for (int index = 0; index <= token.length() - 3; index++) {
            addFeature(vector, "#" + token.substring(index, index + 3), 0.25F);
          }
        }
      }
    }

    if (isZero(vector)) {
      vector[0] = 1.0F;
    }

    return Response.from(Embedding.from(vector));
  }

  public int dimension() {
    return this.dimensions;
  }

  @Override
  public String embeddingIdentity() {
    return "local-hash/" + this.dimensions;
  }

  private static String splitIdentifiers(String value) {
    return value
        .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
        .replace('_', ' ')
        .toLowerCase(Locale.ROOT);
  }

  private static void addFeature(float[] vector, String token, float weight) {
    byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
    int hash = -2128831035;

    for (byte value : bytes) {
      hash ^= value & 255;
      hash *= 16777619;
    }

    int slot = (hash & 2147483647) % vector.length;
    vector[slot] += (hash & 1) == 0 ? weight : -weight;
  }

  private static boolean isZero(float[] vector) {
    for (float value : vector) {
      if (value != 0.0F) {
        return false;
      }
    }

    return true;
  }
}
