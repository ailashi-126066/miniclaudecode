package dev.miniclaudecode.prompt;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministically assembles independent prompt sections. */
public final class PromptPipeline {
  private final List<PromptContributor> contributors;

  public PromptPipeline(List<PromptContributor> contributors) {
    this.contributors =
        List.copyOf(Objects.requireNonNull(contributors, "contributors must not be null")).stream()
            .sorted(
                Comparator.comparingInt(PromptContributor::order)
                    .thenComparing(PromptContributor::id))
            .toList();
  }

  public String build(PromptBuildContext context) {
    Objects.requireNonNull(context, "context must not be null");
    return this.contributors.stream()
        .map(contributor -> contributor.contribute(context))
        .flatMap(java.util.Optional::stream)
        .distinct()
        .reduce((left, right) -> left + System.lineSeparator() + right)
        .orElse("");
  }

  public List<String> contributorIds() {
    return this.contributors.stream().map(PromptContributor::id).toList();
  }
}
