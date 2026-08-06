package dev.miniclaudecode.rag.search;

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
  private static final Pattern CAMEL_CASE = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
  private static final Pattern NON_TOKEN = Pattern.compile("[^\\p{L}\\p{N}]+");
  private static final Set<String> STOP_WORDS =
      Set.of(
          "a", "an", "and", "are", "as", "at", "be", "before", "by", "can", "did", "do", "does",
          "for", "from", "how", "i", "in", "into", "is", "it", "of", "on", "or", "that", "the",
          "this", "through", "to", "was", "were", "when", "where", "which", "while", "with", "的",
          "了", "在", "是", "如何", "怎么", "哪里");
  private static final Set<String> TEST_INTENT =
      Set.of("test", "tests", "testing", "spec", "specification", "fixture", "assertion");
  private static final double LEXICAL_WEIGHT = 0.52;
  private static final double FUSION_WEIGHT = 0.43;
  private static final double SOURCE_WEIGHT = 0.05;
  private static final double SYMBOL_INTENT_WEIGHT = 0.20;

  @Override
  public List<SearchResult> rerank(String query, List<SearchResult> candidates) {
    Set<String> terms = terms(query);
    if (terms.isEmpty() || candidates.isEmpty()) {
      return List.copyOf(candidates);
    }
    Map<String, Integer> documentFrequency = documentFrequency(candidates, terms);
    Map<String, Double> lexicalScores = new HashMap<>();
    double maximumLexical = 0.0;
    double maximumFused = 0.0;
    for (SearchResult candidate : candidates) {
      double lexical = lexicalScore(candidate, terms, documentFrequency, candidates.size());
      lexicalScores.put(candidate.chunk().id(), lexical);
      maximumLexical = Math.max(maximumLexical, lexical);
      maximumFused = Math.max(maximumFused, candidate.fusedScore());
    }

    boolean testIntent = terms.stream().anyMatch(TEST_INTENT::contains);
    List<String> targetSymbol = targetSymbol(query);
    double lexicalScale = maximumLexical;
    double fusedScale = maximumFused;
    List<SearchResult> ordered = new ArrayList<>(candidates);
    ordered.sort(
        Comparator.comparingDouble(
                (SearchResult result) ->
                    combinedScore(
                        result,
                        lexicalScores.getOrDefault(result.chunk().id(), 0.0),
                        lexicalScale,
                        fusedScale,
                        testIntent,
                        targetSymbol))
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
      Set<String> document = Set.copyOf(tokens(searchable(candidate)));
      for (String term : terms) {
        if (document.contains(term)) {
          frequency.merge(term, 1, Integer::sum);
        }
      }
    }
    return frequency;
  }

  private static double lexicalScore(
      SearchResult result,
      Set<String> terms,
      Map<String, Integer> documentFrequency,
      int documents) {
    List<String> symbol = tokens(result.chunk().symbol());
    List<String> path = tokens(result.chunk().path());
    List<String> content = tokens(result.chunk().content());
    double score = 0.0;
    for (String term : terms) {
      double idf = Math.log1p((double) documents / (1 + documentFrequency.getOrDefault(term, 0)));
      score += weightedTfIdf(symbol, term, 5.0, idf);
      score += weightedTfIdf(path, term, 2.5, idf);
      score += weightedTfIdf(content, term, 1.0, idf);
    }
    return score;
  }

  private static double weightedTfIdf(
      List<String> field, String term, double fieldWeight, double idf) {
    int occurrences = 0;
    int first = -1;
    for (int index = 0; index < field.size(); index++) {
      if (field.get(index).equals(term)) {
        occurrences++;
        if (first < 0) {
          first = index;
        }
      }
    }
    if (occurrences == 0) {
      return 0.0;
    }
    // Diminishing returns avoid a repeated comment overwhelming an exact symbol match.
    double termFrequency = 1.0 + Math.log(occurrences);
    double positionBoost = 1.0 + 0.35 / (1.0 + first / 8.0);
    return fieldWeight * idf * termFrequency * positionBoost;
  }

  private static double combinedScore(
      SearchResult result,
      double lexicalScore,
      double maximumLexical,
      double maximumFused,
      boolean testIntent,
      List<String> targetSymbol) {
    double normalizedLexical = maximumLexical == 0.0 ? 0.0 : lexicalScore / maximumLexical;
    double normalizedFused =
        maximumFused == 0.0 ? 0.0 : Math.max(0.0, result.fusedScore()) / maximumFused;
    return LEXICAL_WEIGHT * normalizedLexical
        + FUSION_WEIGHT * normalizedFused
        + SOURCE_WEIGHT * sourceAffinity(result.chunk().path(), testIntent)
        + SYMBOL_INTENT_WEIGHT * symbolAffinity(result, targetSymbol);
  }

  private static double symbolAffinity(SearchResult result, List<String> targetSymbol) {
    List<String> candidate = tokens(result.chunk().symbol());
    return !targetSymbol.isEmpty()
            && candidate.size() >= targetSymbol.size()
            && candidate.subList(0, targetSymbol.size()).equals(targetSymbol)
        ? 1.0
        : 0.0;
  }

  static boolean isExactSymbolMatch(String query, SearchResult result) {
    return symbolAffinity(result, targetSymbol(query)) > 0.0;
  }

  private static double sourceAffinity(String path, boolean testIntent) {
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    boolean testSource =
        normalized.contains("/src/test/")
            || normalized.startsWith("src/test/")
            || normalized.contains("/test/")
            || normalized.endsWith("test.java")
            || normalized.endsWith("tests.java");
    boolean productionSource =
        normalized.contains("/src/main/") || normalized.startsWith("src/main/");
    if (testIntent) {
      return testSource ? 1.0 : productionSource ? -0.25 : 0.0;
    }
    return productionSource ? 1.0 : testSource ? -1.0 : 0.0;
  }

  private static String searchable(SearchResult result) {
    return result.chunk().symbol() + " " + result.chunk().path() + " " + result.chunk().content();
  }

  private static Set<String> terms(String query) {
    Set<String> values = new LinkedHashSet<>();
    for (String term : tokens(query)) {
      if (!STOP_WORDS.contains(term)) {
        values.add(term);
      }
    }
    return Set.copyOf(values);
  }

  private static List<String> targetSymbol(String query) {
    String value = query == null ? "" : query;
    int separator = Math.max(value.lastIndexOf('.'), value.lastIndexOf('#'));
    return separator < 0 ? List.of() : tokens(value.substring(separator + 1));
  }

  private static List<String> tokens(String value) {
    String split = CAMEL_CASE.matcher(value == null ? "" : value).replaceAll(" ");
    String normalized = NON_TOKEN.matcher(split).replaceAll(" ").toLowerCase(Locale.ROOT).trim();
    return normalized.isEmpty() ? List.of() : List.of(normalized.split("\\s+"));
  }
}
