package dev.miniclaudecode.persistence.memory;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Converts a completed, recovered, or failed turn into a concise candidate lesson; curation happens
 * in a {@link MemoryStore}.
 */
public final class ReflexionExtractor {
  private final Clock clock;
  private final Function<String, Optional<String>> lessonGenerator;

  private static final String FALLBACK_FAILURE_LESSON =
      "Before retrying, inspect the reported failure and verify the smallest corrective step; do not claim completion without a passing verification.";
  private static final String FALLBACK_SUCCESS_LESSON =
      "The change was completed only after a recorded verification command succeeded; reuse the smallest relevant verification step for similar work.";

  public ReflexionExtractor(Clock clock) {
    this(clock, context -> Optional.empty());
  }

  /**
   * @param lessonGenerator accepts a compact context string, returns an LLM-generated lesson or
   *     empty to fall back to the hardcoded default. Implementations should handle errors
   *     internally and return empty on failure.
   */
  public ReflexionExtractor(Clock clock, Function<String, Optional<String>> lessonGenerator) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.lessonGenerator =
        Objects.requireNonNull(lessonGenerator, "lessonGenerator must not be null");
  }

  public Optional<AceBullet> extract(
      List<AgentMessage> messages, AgentStatus status, String terminalError) {
    boolean verifiedChange =
        status == AgentStatus.COMPLETED
            && messages.stream()
                .filter(ToolMessage.class::isInstance)
                .map(ToolMessage.class::cast)
                .anyMatch(
                    message ->
                        "shell:run".equals(message.qualifiedToolName())
                            && !message.error()
                            && message.text().startsWith("[verification-command-succeeded]"));
    if (status != AgentStatus.FAILED && status != AgentStatus.CANCELLED) {
      boolean recoveredToolFailure =
          status == AgentStatus.COMPLETED
              && messages.stream()
                  .filter(ToolMessage.class::isInstance)
                  .map(ToolMessage.class::cast)
                  .anyMatch(ToolMessage::error);
      if (!recoveredToolFailure && !verifiedChange) {
        return Optional.empty();
      }
    }
    String objective =
        messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::text)
            .reduce((left, right) -> right)
            .orElse("agent task");
    List<String> failures =
        messages.stream()
            .filter(ToolMessage.class::isInstance)
            .map(ToolMessage.class::cast)
            .filter(ToolMessage::error)
            .map(message -> message.qualifiedToolName() + ": " + compact(message.text(), 300))
            .limit(4)
            .toList();
    String cause = failures.isEmpty() ? compact(terminalError, 300) : failures.getLast();
    if (cause.isBlank()) {
      cause = "turn ended before a verified completion";
    }

    // Build context for LLM lesson generation
    String context = buildContext(objective, status, cause, failures, verifiedChange);
    String fallback =
        status == AgentStatus.COMPLETED && verifiedChange
            ? FALLBACK_SUCCESS_LESSON
            : FALLBACK_FAILURE_LESSON;
    String lesson = this.lessonGenerator.apply(context).orElse(fallback);

    String trigger =
        failures.isEmpty()
            ? (verifiedChange ? "verified workspace change" : status.name().toLowerCase() + " turn")
            : "tool failure: " + toolName(failures.getLast());
    String safeEvidence =
        status == AgentStatus.COMPLETED && failures.isEmpty()
            ? "verification command succeeded"
            : failures.isEmpty()
                ? "turn ended without verified completion"
                : "tool failure observed: " + toolName(failures.getLast());
    return Optional.of(
        new AceBullet(
            null,
            trigger,
            lesson,
            List.of(safeEvidence),
            1,
            this.clock.instant(),
            this.clock.instant()));
  }

  private static String buildContext(
      String objective,
      AgentStatus status,
      String cause,
      List<String> failures,
      boolean verifiedChange) {
    StringBuilder context = new StringBuilder();
    context.append("Objective: ").append(objective).append("\n");
    context.append("Status: ").append(status).append("\n");
    if (verifiedChange) {
      context.append("Outcome: verification command succeeded\n");
    }
    if (!failures.isEmpty()) {
      context.append("Failures:\n");
      failures.forEach(failure -> context.append("- ").append(failure).append("\n"));
    }
    context.append("Root cause: ").append(cause).append("\n");
    return context.toString();
  }

  private static String compact(String value, int maximum) {
    String normalized = Objects.requireNonNullElse(value, "").replaceAll("\\s+", " ").strip();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "...";
  }

  private static String toolName(String failure) {
    int separator = failure.indexOf(": ");
    return separator < 0 ? failure : failure.substring(0, separator);
  }
}
