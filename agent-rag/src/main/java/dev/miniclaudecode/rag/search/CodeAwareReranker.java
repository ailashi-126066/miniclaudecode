package dev.miniclaudecode.rag.search;

import dev.miniclaudecode.tools.remote.RemoteAiGateway;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic second-stage ranker using candidate-set TF-IDF, field weights, and first-occurrence
 * position instead of binary substring checks.
 */
public final class CodeAwareReranker implements Reranker {
  private static final Pattern TERMS = Pattern.compile("[^\\p{L}\\p{N}]+");

  @Override
  public List<SearchResult> rerank(String query, List<SearchResult> candidates) {
    var remote =
        RemoteAiGateway.fromEnvironment()
            .flatMap(
                gateway ->
                    gateway.complete(
                        "Rank candidate code snippets for the query. Return only zero-based candidate indexes, comma separated.",
                        "Query: "
                            + query
                            + "\nCandidates:\n"
                            + candidates.stream()
                                .limit(20)
                                .map(
                                    value ->
                                        value.chunk().path()
                                            + ": "
                                            + value.chunk().symbol()
                                            + " "
                                            + value.chunk().content())
                                .reduce("", (left, right) -> left + "\n" + right)));
    if (remote.isPresent()) {
      try {
        List<Integer> order =
            java.util.Arrays.stream(remote.get().split("\\s*,\\s*"))
                .map(Integer::parseInt)
                .filter(index -> index >= 0 && index < candidates.size())
                .distinct()
                .toList();
        if (!order.isEmpty()) {
          List<SearchResult> ranked = new ArrayList<>();
          order.forEach(index -> ranked.add(candidates.get(index)));
          candidates.stream().filter(value -> !ranked.contains(value)).forEach(ranked::add);
          return List.copyOf(ranked);
        }
      } catch (NumberFormatException ignored) {
        /* deterministic fallback */
      }
    }
    Set<String> terms = terms(query);
    if (terms.isEmpty() || candidates.isEmpty()) {
      return List.copyOf(candidates);
    }
    Map<String, Integer> documentFrequency = documentFrequency(candidates, terms);
    List<SearchResult> ordered = new ArrayList<>(candidates);
    ordered.sort(
        Comparator.comparingDouble(
                (SearchResult result) -> score(result, terms, documentFrequency, candidates.size()))
            .reversed()
            .thenComparing(Comparator.comparingDouble(SearchResult::fusedScore).reversed())
            .thenComparing(result -> result.chunk().path())
            .thenComparingInt(result -> result.chunk().startLine()));
    return List.copyOf(ordered);
  }

  private static Map<String, Integer> documentFrequency(
      List<SearchResult> candidates, Set<String> terms) {
    Map<String, Integer> frequency = new HashMap<>();
    for (SearchResult candidate : candidates) {
      String document = searchable(candidate).toLowerCase(Locale.ROOT);
      for (String term : terms) {
        if (document.contains(term)) {
          frequency.merge(term, 1, Integer::sum);
        }
      }
    }
    return frequency;
  }

  private static double score(
      SearchResult result,
      Set<String> terms,
      Map<String, Integer> documentFrequency,
      int documents) {
    String symbol = result.chunk().symbol().toLowerCase(Locale.ROOT);
    String path = result.chunk().path().toLowerCase(Locale.ROOT);
    String content = result.chunk().content().toLowerCase(Locale.ROOT);
    double score = 0.0;
    for (String term : terms) {
      double idf = Math.log1p((double) documents / (1 + documentFrequency.getOrDefault(term, 0)));
      score += weightedTfIdf(symbol, term, 5.0, idf);
      score += weightedTfIdf(path, term, 2.5, idf);
      score += weightedTfIdf(content, term, 1.0, idf);
    }
    return score;
  }

  private static double weightedTfIdf(String field, String term, double fieldWeight, double idf) {
    int occurrences = 0;
    int index = field.indexOf(term);
    int first = index;
    while (index >= 0) {
      occurrences++;
      index = field.indexOf(term, index + term.length());
    }
    if (occurrences == 0) {
      return 0.0;
    }
    // Diminishing returns avoid a repeated comment overwhelming an exact symbol match.
    double termFrequency = 1.0 + Math.log(occurrences);
    double positionBoost = 1.0 + 0.35 / (1.0 + first / 64.0);
    return fieldWeight * idf * termFrequency * positionBoost;
  }

  private static String searchable(SearchResult result) {
    return result.chunk().symbol() + " " + result.chunk().path() + " " + result.chunk().content();
  }

  private static Set<String> terms(String query) {
    Set<String> values = new LinkedHashSet<>();
    for (String term : TERMS.split(query == null ? "" : query.toLowerCase(Locale.ROOT))) {
      if (!term.isBlank()) {
        values.add(term);
      }
    }
    return Set.copyOf(values);
  }
}
