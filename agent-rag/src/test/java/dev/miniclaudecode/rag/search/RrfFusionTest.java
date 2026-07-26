package dev.miniclaudecode.rag.search;

import dev.miniclaudecode.rag.chunk.CodeChunk;
import dev.miniclaudecode.rag.chunk.CodeChunk.Kind;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.MapAssert;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class RrfFusionTest {
  @Test
  void fusesRanksByStableChunkIdentityAndKeepsRouteEvidence() {
    CodeChunk shared = chunk("shared", "Shared.java");
    CodeChunk lexical = chunk("lexical", "Lexical.java");
    CodeChunk semantic = chunk("semantic", "Semantic.java");
    List<RetrievalHit> bm25 =
        List.of(
            new RetrievalHit(shared, 8.2, 1, RetrievalRoute.BM25),
            new RetrievalHit(lexical, 5.0, 2, RetrievalRoute.BM25));
    List<RetrievalHit> vector =
        List.of(
            new RetrievalHit(semantic, 0.95, 1, RetrievalRoute.VECTOR),
            new RetrievalHit(shared, 0.9, 2, RetrievalRoute.VECTOR));
    List<SearchResult> results = new RrfFusion(60, 1.0, 1.0).fuse(bm25, vector);
    Assertions.assertThat(results).hasSize(3);
    Assertions.assertThat(results.getFirst().chunk().id()).isEqualTo("shared");
    Assertions.assertThat(results.getFirst().fusedScore())
        .isCloseTo(0.03252247488101534, Offset.offset(1.0E-6));
    ((MapAssert)
            Assertions.assertThat(results.getFirst().ranks()).containsEntry(RetrievalRoute.BM25, 1))
        .containsEntry(RetrievalRoute.VECTOR, 2);
    Assertions.assertThat(results.getFirst().explanation())
        .contains(new CharSequence[] {"BM25 rank=1", "vector rank=2"});
  }

  private static CodeChunk chunk(String id, String path) {
    return new CodeChunk(
        id, path, "java", Kind.METHOD, "example", "Owner", id + "()", 1, 1, "void " + id + "() {}");
  }
}
