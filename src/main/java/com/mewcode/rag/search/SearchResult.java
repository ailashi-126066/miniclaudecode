package com.mewcode.rag.search;

import com.mewcode.rag.chunk.CodeChunk;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record SearchResult(
    CodeChunk chunk,
    double fusedScore,
    Map<RetrievalRoute, Integer> ranks,
    Map<RetrievalRoute, Double> rawScores) {
  public SearchResult(
      CodeChunk chunk,
      double fusedScore,
      Map<RetrievalRoute, Integer> ranks,
      Map<RetrievalRoute, Double> rawScores) {
    Objects.requireNonNull(chunk, "chunk must not be null");
    if (Double.isFinite(fusedScore) && !(fusedScore < 0.0)) {
      ranks = Map.copyOf(Objects.requireNonNull(ranks, "ranks must not be null"));
      rawScores = Map.copyOf(Objects.requireNonNull(rawScores, "rawScores must not be null"));
      this.chunk = chunk;
      this.fusedScore = fusedScore;
      this.ranks = ranks;
      this.rawScores = rawScores;
    } else {
      throw new IllegalArgumentException("fusedScore must be finite and non-negative");
    }
  }

  public String explanation() {
    return "RRF="
        + String.format(Locale.ROOT, "%.6f", this.fusedScore)
        + ", BM25 rank="
        + this.ranks.getOrDefault(RetrievalRoute.BM25, -1)
        + ", vector rank="
        + this.ranks.getOrDefault(RetrievalRoute.VECTOR, -1);
  }
}
