package dev.miniclaudecode.prompt;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** A named prompt section plugin. Smaller order values render first. */
public interface PromptContributor {
  String id();

  int order();

  Optional<String> contribute(PromptBuildContext context);

  static PromptContributor of(
      String id, int order, Function<PromptBuildContext, String> contributor) {
    String normalizedId = requireText(id);
    Objects.requireNonNull(contributor, "contributor must not be null");
    return new PromptContributor() {
      @Override
      public String id() {
        return normalizedId;
      }

      @Override
      public int order() {
        return order;
      }

      @Override
      public Optional<String> contribute(PromptBuildContext context) {
        return Optional.ofNullable(contributor.apply(context))
            .map(String::strip)
            .filter(value -> !value.isEmpty());
      }
    };
  }

  private static String requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("prompt contributor id must not be blank");
    }
    return value.trim();
  }
}
