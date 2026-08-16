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

  /**
   * Entries the deny layer adds on top of {@link CommandRiskClassifier#systemDestructiveMarkers()}.
   *
   * <p>These are repository-destructive rather than machine-destructive, so the classifier rates
   * them HIGH (approvable) while the default denylist refuses them outright: recovering a wiped
   * working tree costs the user real work, and no plausible task needs the agent to ask.
   */
  private static final List<String> UNRECOVERABLE_WORKSPACE_FRAGMENTS =
      List.of(
          "rm -rf", "rm -fr", "remove-item -recurse -force", "git reset --hard", "git clean -f");

  /**
   * The shipped policy. The deny half is derived from the classifier's destructive vocabulary so a
   * marker cannot be added to one layer and forgotten in the other; the allow half is the read-only
   * shortlist that skips classification entirely.
   */
  public static CommandPolicy defaults() {
    List<String> denyFragments =
        java.util.stream.Stream.concat(
                CommandRiskClassifier.systemDestructiveMarkers().stream(),
                UNRECOVERABLE_WORKSPACE_FRAGMENTS.stream())
            .toList();
    return new CommandPolicy(
        List.of("rg", "git status", "git diff", "git log", "get-content", "get-childitem"),
        denyFragments,
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
