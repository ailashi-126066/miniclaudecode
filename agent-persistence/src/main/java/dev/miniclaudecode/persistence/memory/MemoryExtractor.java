package dev.miniclaudecode.persistence.memory;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministically distills a completed turn into a compact, reusable memory asset. */
public final class MemoryExtractor {
  private static final String CHANGE_PREFIX = "Applied approved change to ";
  private final Clock clock;

  public MemoryExtractor(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public Optional<MemoryRecord> extract(
      SessionId session,
      TurnId turn,
      List<AgentMessage> messages,
      String finalText,
      AgentStatus status) {
    Objects.requireNonNull(messages, "messages must not be null");
    if (status != AgentStatus.COMPLETED) {
      return Optional.empty();
    }
    String objective =
        messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::text)
            .reduce((left, right) -> right)
            .orElse("");
    if (objective.isBlank()) {
      return Optional.empty();
    }

    Optional<String> preference = explicitPreference(objective);
    if (preference.isPresent()) {
      return Optional.of(
          new MemoryRecord(
              null,
              MemoryRecord.Category.USER_PREFERENCE,
              abbreviate(objective, 400),
              preference.orElseThrow(),
              List.of("Explicitly requested by the user"),
              session,
              turn,
              this.clock.instant()));
    }

    Set<String> changed = new LinkedHashSet<>();
    List<String> failures = new ArrayList<>();
    List<String> verification = new ArrayList<>();
    int latestUser = 0;
    for (int index = 0; index < messages.size(); index++) {
      if (messages.get(index) instanceof UserMessage) {
        latestUser = index;
      }
    }
    for (AgentMessage message : messages.subList(latestUser, messages.size())) {
      if (message instanceof ToolMessage tool) {
        if (tool.error()) {
          failures.add(tool.qualifiedToolName() + ": " + abbreviate(tool.text(), 220));
        } else if (isMutation(tool.qualifiedToolName())) {
          String normalized = tool.text().replaceAll("\\s+", " ").trim();
          changed.add(
              normalized.startsWith(CHANGE_PREFIX)
                  ? normalized.substring(CHANGE_PREFIX.length())
                  : abbreviate(normalized, 160));
        } else if ("shell:run".equals(tool.qualifiedToolName())) {
          verification.add(abbreviate(tool.text(), 220));
        }
      }
    }
    if (changed.isEmpty() && failures.isEmpty() && finalText.isBlank()) {
      return Optional.empty();
    }

    List<String> evidence = new ArrayList<>();
    changed.forEach(path -> evidence.add("changed: " + path));
    tail(failures, 3).forEach(value -> evidence.add("repaired-after: " + value));
    tail(verification, 2).forEach(value -> evidence.add("verified: " + value));
    MemoryRecord.Category category =
        !failures.isEmpty()
            ? MemoryRecord.Category.ERROR_REPAIR
            : (!changed.isEmpty()
                ? MemoryRecord.Category.PATH_EXPERIENCE
                : MemoryRecord.Category.SESSION_OUTCOME);
    return Optional.of(
        new MemoryRecord(
            null,
            category,
            abbreviate(objective, 400),
            abbreviate(finalText, 700),
            evidence,
            session,
            turn,
            this.clock.instant()));
  }

  private static Optional<String> explicitPreference(String objective) {
    String stripped = objective.strip();
    for (String prefix : List.of("记住：", "记住:", "remember:", "Remember:")) {
      if (stripped.startsWith(prefix) && stripped.length() > prefix.length()) {
        return Optional.of(abbreviate(stripped.substring(prefix.length()).strip(), 700));
      }
    }
    return Optional.empty();
  }

  private static boolean isMutation(String name) {
    return "workspace:write".equals(name)
        || "workspace:edit".equals(name)
        || "workspace:apply_patch".equals(name);
  }

  private static List<String> tail(List<String> values, int maximum) {
    return values.subList(Math.max(0, values.size() - maximum), values.size());
  }

  private static String abbreviate(String value, int maximum) {
    String normalized = Objects.requireNonNullElse(value, "").replaceAll("\\s+", " ").trim();
    if (normalized.isBlank()) {
      return "(no final narrative)";
    }
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "...";
  }
}
