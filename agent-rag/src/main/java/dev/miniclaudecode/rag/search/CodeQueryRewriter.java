package dev.miniclaudecode.rag.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small, deterministic query expansion for source code.
 *
 * <p>It deliberately does not call a model: a search must remain usable offline and each extra
 * vector lookup must be visible and bounded. The original query is always first; expansions only
 * expose identifier words which are otherwise glued together in camelCase, snake_case or paths.
 */
public final class CodeQueryRewriter {
  private static final Pattern CAMEL_CASE = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");
  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  public QueryPlan rewrite(String query) {
    String original = Objects.requireNonNull(query, "query must not be null").trim();
    if (original.isEmpty()) {
      throw new IllegalArgumentException("query must not be blank");
    }

    Set<String> variants = new LinkedHashSet<>();
    variants.add(original);
    String identifierWords = identifierWords(original);
    if (!identifierWords.equalsIgnoreCase(original)) {
      variants.add(identifierWords);
    }
    return new QueryPlan(original, List.copyOf(variants));
  }

  private static String identifierWords(String query) {
    return NON_WORD
        .matcher(CAMEL_CASE.matcher(query).replaceAll(" "))
        .replaceAll(" ")
        .trim()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  public record QueryPlan(String original, List<String> variants) {
    public QueryPlan {
      original = Objects.requireNonNull(original, "original must not be null");
      variants = List.copyOf(Objects.requireNonNull(variants, "variants must not be null"));
      if (variants.isEmpty() || !variants.getFirst().equals(original)) {
        throw new IllegalArgumentException("first query variant must be the original query");
      }
    }
  }
}
