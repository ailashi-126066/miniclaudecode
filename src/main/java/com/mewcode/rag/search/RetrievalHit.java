package com.mewcode.rag.search;

import com.mewcode.rag.chunk.CodeChunk;
import java.util.Objects;

public record RetrievalHit(CodeChunk chunk, double score, int rank, RetrievalRoute route) {
  public RetrievalHit(CodeChunk chunk, double score, int rank, RetrievalRoute route) {
    Objects.requireNonNull(chunk, "chunk must not be null");
    Objects.requireNonNull(route, "route must not be null");
    if (Double.isFinite(score) && rank >= 1) {
      this.chunk = chunk;
      this.score = score;
      this.rank = rank;
      this.route = route;
    } else {
      throw new IllegalArgumentException("invalid retrieval score or rank");
    }
  }
}
