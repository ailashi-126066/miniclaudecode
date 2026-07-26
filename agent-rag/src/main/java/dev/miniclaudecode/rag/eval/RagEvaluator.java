package dev.miniclaudecode.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.rag.search.SearchResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

public final class RagEvaluator {
  private static final ObjectMapper JSON = new ObjectMapper();

  public List<RagEvaluator.EvaluationCase> load(Path jsonLines) throws IOException {
    List<RagEvaluator.EvaluationCase> cases = new ArrayList<>();

    String line;
    try (BufferedReader reader = Files.newBufferedReader(jsonLines, StandardCharsets.UTF_8)) {
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          cases.add(
              (RagEvaluator.EvaluationCase)
                  JSON.readValue(line, RagEvaluator.EvaluationCase.class));
        }
      }
    }

    return List.copyOf(cases);
  }

  public RagEvaluator.EvaluationReport evaluate(
      List<RagEvaluator.EvaluationCase> cases, Map<String, RagEvaluator.SearchStrategy> strategies)
      throws IOException {
    if (!cases.isEmpty() && !strategies.isEmpty()) {
      Map<String, RagEvaluator.EvaluationMetrics> metrics = new LinkedHashMap<>();

      for (Entry<String, RagEvaluator.SearchStrategy> strategy : strategies.entrySet()) {
        metrics.put(strategy.getKey(), evaluate(cases, strategy.getValue()));
      }

      return new RagEvaluator.EvaluationReport(metrics);
    } else {
      throw new IllegalArgumentException("evaluation cases and strategies must not be empty");
    }
  }

  private static RagEvaluator.EvaluationMetrics evaluate(
      List<RagEvaluator.EvaluationCase> cases, RagEvaluator.SearchStrategy strategy)
      throws IOException {
    double recall5 = 0.0;
    double recall10 = 0.0;
    double reciprocalRanks = 0.0;
    long[] latencies = new long[cases.size()];

    for (int index = 0; index < cases.size(); index++) {
      RagEvaluator.EvaluationCase evaluationCase = cases.get(index);
      long started = System.nanoTime();
      List<SearchResult> results = strategy.search(evaluationCase.query());
      latencies[index] = Math.max(0L, (System.nanoTime() - started) / 1000000L);
      recall5 += recallAt(results, evaluationCase.relevantChunkIds(), 5);
      recall10 += recallAt(results, evaluationCase.relevantChunkIds(), 10);
      reciprocalRanks += reciprocalRank(results, evaluationCase.relevantChunkIds());
    }

    Arrays.sort(latencies);
    return new RagEvaluator.EvaluationMetrics(
        recall5 / (double) cases.size(),
        recall10 / (double) cases.size(),
        reciprocalRanks / (double) cases.size(),
        percentile(latencies, 0.5),
        percentile(latencies, 0.95),
        cases.size());
  }

  private static double recallAt(List<SearchResult> results, Set<String> relevant, int limit) {
    long found =
        results.stream()
            .limit((long) limit)
            .map(result -> result.chunk().id())
            .filter(relevant::contains)
            .distinct()
            .count();
    return (double) found / (double) relevant.size();
  }

  private static double reciprocalRank(List<SearchResult> results, Set<String> relevant) {
    for (int index = 0; index < results.size(); index++) {
      if (relevant.contains(results.get(index).chunk().id())) {
        return 1.0 / (double) (index + 1);
      }
    }

    return 0.0;
  }

  private static long percentile(long[] sorted, double percentile) {
    int index = (int) Math.ceil(percentile * (double) sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }

  public static record EvaluationCase(String query, Set<String> relevantChunkIds) {
    public EvaluationCase(String query, Set<String> relevantChunkIds) {
      if (query != null && !query.isBlank()) {
        relevantChunkIds = Set.copyOf(Objects.requireNonNull(relevantChunkIds));
        if (relevantChunkIds.isEmpty()) {
          throw new IllegalArgumentException("relevantChunkIds must not be empty");
        } else {
          this.query = query;
          this.relevantChunkIds = relevantChunkIds;
        }
      } else {
        throw new IllegalArgumentException("query must not be blank");
      }
    }
  }

  public static record EvaluationMetrics(
      double recallAt5,
      double recallAt10,
      double meanReciprocalRank,
      long p50LatencyMillis,
      long p95LatencyMillis,
      int cases) {}

  public static record EvaluationReport(Map<String, RagEvaluator.EvaluationMetrics> strategies) {
    public EvaluationReport(Map<String, RagEvaluator.EvaluationMetrics> strategies) {
      strategies = Map.copyOf(strategies);
      this.strategies = strategies;
    }
  }

  @FunctionalInterface
  public interface SearchStrategy {
    List<SearchResult> search(String query) throws IOException;
  }
}
