package dev.miniclaudecode.persistence.memory;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Converts a failed turn into a concise candidate lesson; curation happens in {@link
 * AceBulletStore}.
 */
public final class ReflexionExtractor {
  private final Clock clock;

  public ReflexionExtractor(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public Optional<AceBullet> extract(
      List<AgentMessage> messages, AgentStatus status, String terminalError) {
    if (status != AgentStatus.FAILED && status != AgentStatus.CANCELLED) {
      boolean recoveredToolFailure =
          status == AgentStatus.COMPLETED
              && messages.stream()
                  .filter(ToolMessage.class::isInstance)
                  .map(ToolMessage.class::cast)
                  .anyMatch(ToolMessage::error);
      if (!recoveredToolFailure) {
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
    String lesson =
        "Before retrying, inspect the reported failure and verify the smallest corrective step; do not claim completion without a passing verification.";
    return Optional.of(
        new AceBullet(
            null,
            compact(objective, 300),
            lesson,
            List.of(
                (status == AgentStatus.COMPLETED ? "recovered failure: " : "failure: ") + cause),
            1,
            this.clock.instant(),
            this.clock.instant()));
  }

  private static String compact(String value, int maximum) {
    String normalized = Objects.requireNonNullElse(value, "").replaceAll("\\s+", " ").strip();
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "...";
  }
}
