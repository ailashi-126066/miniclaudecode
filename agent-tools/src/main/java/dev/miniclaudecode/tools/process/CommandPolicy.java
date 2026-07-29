package dev.miniclaudecode.tools.process;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Configurable shell allow/deny layer evaluated before risk classification and user approval.
 *
 * <p>Deny fragments always win. An unmatched command is reviewed by the existing classifier unless
 * strict allowlist-only mode is enabled.
 */
public final class CommandPolicy {
  private final List<String> allowPrefixes;
  private final List<String> denyFragments;
  private final boolean allowlistOnly;

  public CommandPolicy(
      List<String> allowPrefixes, List<String> denyFragments, boolean allowlistOnly) {
    this.allowPrefixes = normalize(allowPrefixes);
    this.denyFragments = normalize(denyFragments);
    this.allowlistOnly = allowlistOnly;
  }

  public static CommandPolicy defaults() {
    return new CommandPolicy(
        List.of("rg", "git status", "git diff", "git log", "get-content", "get-childitem"),
        List.of(
            "rm -rf",
            "git reset --hard",
            "git clean -f",
            "format ",
            "diskpart",
            "shutdown",
            "reboot",
            "remove-item -recurse -force"),
        false);
  }

  public Decision evaluate(String command) {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command must not be blank");
    }
    String normalized = normalizeCommand(command);
    if (this.denyFragments.stream().anyMatch(fragment -> matchesDeny(normalized, fragment))) {
      return Decision.DENY;
    }
    if (this.allowPrefixes.stream().anyMatch(prefix -> matchesPrefix(normalized, prefix))) {
      return Decision.ALLOW;
    }
    return this.allowlistOnly ? Decision.DENY : Decision.REVIEW;
  }

  private static boolean matchesPrefix(String command, String prefix) {
    return command.equals(prefix) || command.startsWith(prefix + " ");
  }

  private static boolean matchesDeny(String command, String fragment) {
    if (fragment.matches("[a-z0-9_.-]+")) {
      return matchesPrefix(command, fragment);
    }
    return command.contains(fragment);
  }

  private static String normalizeCommand(String value) {
    return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  private static List<String> normalize(List<String> values) {
    return List.copyOf(Objects.requireNonNull(values, "policy values must not be null")).stream()
        .filter(Objects::nonNull)
        .map(CommandPolicy::normalizeCommand)
        .filter(value -> !value.isEmpty())
        .distinct()
        .toList();
  }

  public enum Decision {
    ALLOW,
    DENY,
    REVIEW
  }
}
