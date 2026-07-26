package dev.miniclaudecode.rag.embedding;

import dev.langchain4j.data.embedding.Embedding;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LocalCodeEmbeddingModelTest {
  @Test
  void createsDeterministicOfflineCodeAwareVectors() {
    LocalCodeEmbeddingModel model = new LocalCodeEmbeddingModel(64);
    float[] first = ((Embedding) model.embed("findUserById user_id").content()).vector();
    float[] second = ((Embedding) model.embed("find User By Id").content()).vector();
    Assertions.assertThat(first).hasSize(64);
    Assertions.assertThat(dot(first, second)).isGreaterThan(0.0);
    Assertions.assertThat(((Embedding) model.embed("findUserById user_id").content()).vector())
        .containsExactly(first);
    Assertions.assertThat(model.dimension()).isEqualTo(64);
  }

  private static double dot(float[] left, float[] right) {
    double value = 0.0;

    for (int index = 0; index < left.length; index++) {
      value += (double) (left[index] * right[index]);
    }

    return value;
  }
}
