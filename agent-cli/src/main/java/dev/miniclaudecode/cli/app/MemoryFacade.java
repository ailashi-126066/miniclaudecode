package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.persistence.memory.AceBullet;
import dev.miniclaudecode.persistence.memory.AceBulletStore;
import dev.miniclaudecode.persistence.memory.UserProfileStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One entry point from a turn into layered memory stores. */
final class MemoryFacade {
  private static final int ACTIVE_PROFILE_LIMIT = 20;
  private final UserProfileStore profile;
  private final ClaudeInstructions instructions;
  private final AceBulletStore bullets;

  MemoryFacade(UserProfileStore profile, ClaudeInstructions instructions, AceBulletStore bullets) {
    this.profile = Objects.requireNonNull(profile, "profile must not be null");
    this.instructions = Objects.requireNonNull(instructions, "instructions must not be null");
    this.bullets = Objects.requireNonNull(bullets, "bullets must not be null");
  }

  String memoryContextForTurn(String prompt) {
    StringBuilder context = new StringBuilder();
    List<String> activePreferences = activePreferences();
    if (!activePreferences.isEmpty()) {
      context
          .append("Active user preferences follow. They are explicit user instructions:\n")
          .append(
              activePreferences.stream()
                  .map(value -> "- " + value)
                  .reduce((left, right) -> left + "\n" + right)
                  .orElse(""))
          .append("\n\n");
    }
    String claude = this.instructions.load();
    if (!claude.isBlank()) {
      context.append(claude).append("\n\n");
    }
    List<AceBullet> lessons = this.bullets.search(prompt, 3);
    if (!lessons.isEmpty()) {
      context.append("Relevant project lessons follow; verify them against the workspace:\n");
      lessons.forEach(
          lesson ->
              context
                  .append("- [confidence=")
                  .append(String.format(java.util.Locale.ROOT, "%.2f", lesson.confidence()))
                  .append("] ")
                  .append(lesson.lesson())
                  .append(" (trigger: ")
                  .append(lesson.trigger())
                  .append(")\n"));
    }
    return context.toString().strip();
  }

  Optional<String> rememberExplicitPreference(List<AgentMessage> messages) {
    Optional<String> preference =
        messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .map(UserMessage::text)
            .reduce((ignored, latest) -> latest)
            .flatMap(MemoryFacade::explicitPreference);
    return preference.filter(this.profile::add);
  }

  Optional<String> approveExplicitCandidate(String prompt) {
    String text = Objects.requireNonNullElse(prompt, "").strip();
    for (String prefix : List.of("批准记忆：", "批准记忆:", "approve memory:", "Approve memory:")) {
      if (text.startsWith(prefix)) {
        String id = text.substring(prefix.length()).trim();
        if (!id.isBlank() && this.bullets.approve(id)) {
          return Optional.of(id);
        }
      }
    }
    return Optional.empty();
  }

  private List<String> activePreferences() {
    List<String> all = this.profile.list();
    return all.subList(Math.max(0, all.size() - ACTIVE_PROFILE_LIMIT), all.size());
  }

  private static Optional<String> explicitPreference(String prompt) {
    String text = Objects.requireNonNullElse(prompt, "").strip();
    for (String prefix : List.of("记住：", "记住:", "remember:", "Remember:")) {
      if (text.startsWith(prefix) && text.length() > prefix.length()) {
        String preference = text.substring(prefix.length()).replaceAll("\\s+", " ").strip();
        return preference.isBlank() ? Optional.empty() : Optional.of(preference);
      }
    }
    return Optional.empty();
  }
}
