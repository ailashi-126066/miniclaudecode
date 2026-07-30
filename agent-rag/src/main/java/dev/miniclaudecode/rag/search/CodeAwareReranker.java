package dev.miniclaudecode.rag.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic second-stage ranking which rewards exact symbol and path matches. */
public final class CodeAwareReranker implements Reranker {
  private static final Pattern TERMS = Pattern.compile("[^\\p{L}\\p{N}]+");

  @Override
  public List<SearchResult> rerank(String query, List<SearchResult> candidates) {
    Set<String> terms = new LinkedHashSet<>(List.of(TERMS.split(query.toLowerCase(Locale.ROOT))));
    List<SearchResult> ordered = new ArrayList<>(candidates);
    ordered.sort(
        Comparator.comparingDouble((SearchResult result) -> lexicalScore(result, terms))
            .reversed()
            .thenComparing(Comparator.comparingDouble(SearchResult::fusedScore).reversed())
            .thenComparing(result -> result.chunk().path())
            .thenComparingInt(result -> result.chunk().startLine()));
    return List.copyOf(ordered);
  }

  private static double lexicalScore(SearchResult result, Set<String> terms) {
    String symbol = result.chunk().symbol().toLowerCase(Locale.ROOT);
    String path = result.chunk().path().toLowerCase(Locale.ROOT);
    String content = result.chunk().content().toLowerCase(Locale.ROOT);
    double score = 0.0;
    for (String term : terms) {
      if (term.isBlank()) {
        continue;
      }
      if (symbol.contains(term)) {
        score += 4.0;
      }
      if (path.contains(term)) {
        score += 2.0;
      }
      if (content.contains(term)) {
        score += 1.0;
      }
    }
    return score;
  }
}
