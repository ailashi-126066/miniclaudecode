package dev.miniclaudecode.rag.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RrfFusion {
  private final int rankConstant;
  private final double bm25Weight;
  private final double vectorWeight;

  public RrfFusion() {
    this(60, 1.0, 1.0);
  }

  public RrfFusion(int rankConstant, double bm25Weight, double vectorWeight) {
    if (rankConstant >= 1 && !(bm25Weight < 0.0) && !(vectorWeight < 0.0)) {
      this.rankConstant = rankConstant;
      this.bm25Weight = bm25Weight;
      this.vectorWeight = vectorWeight;
    } else {
      throw new IllegalArgumentException("invalid RRF configuration");
    }
  }

  public List<SearchResult> fuse(List<RetrievalHit> bm25, List<RetrievalHit> vector) {
    Map<String, RrfFusion.Accumulator> accumulators = new LinkedHashMap<>();
    this.add(accumulators, bm25, this.bm25Weight);
    this.add(accumulators, vector, this.vectorWeight);
    return accumulators.values().stream()
        .map(RrfFusion.Accumulator::result)
        .sorted(
            Comparator.comparingDouble(SearchResult::fusedScore)
                .reversed()
                .thenComparing(result -> result.chunk().path())
                .thenComparingInt(result -> result.chunk().startLine()))
        .toList();
  }

  private void add(
      Map<String, RrfFusion.Accumulator> values, List<RetrievalHit> hits, double weight) {
    for (RetrievalHit hit : hits) {
      RrfFusion.Accumulator accumulator =
          values.computeIfAbsent(hit.chunk().id(), ignored -> new RrfFusion.Accumulator(hit));
      accumulator.add(hit, weight / (double) (this.rankConstant + hit.rank()));
    }
  }

  private static final class Accumulator {
    private final RetrievalHit first;
    private final Map<RetrievalRoute, Integer> ranks = new EnumMap<>(RetrievalRoute.class);
    private final Map<RetrievalRoute, Double> rawScores = new EnumMap<>(RetrievalRoute.class);
    private final List<Double> contributions = new ArrayList<>();

    private Accumulator(RetrievalHit first) {
      this.first = first;
    }

    private void add(RetrievalHit hit, double contribution) {
      this.ranks.merge(hit.route(), hit.rank(), Math::min);
      this.rawScores.merge(hit.route(), hit.score(), Math::max);
      this.contributions.add(contribution);
    }

    private SearchResult result() {
      return new SearchResult(
          this.first.chunk(),
          this.contributions.stream().mapToDouble(Double::doubleValue).sum(),
          this.ranks,
          this.rawScores);
    }
  }
}
