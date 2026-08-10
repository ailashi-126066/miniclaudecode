package dev.miniclaudecode.persistence.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Literal command policy loaded from YAML; execution logic remains in the tools module. */
public record CommandPolicyConfig(
    List<String> allowPrefixes, List<String> denyFragments, boolean allowlistOnly) {
  public CommandPolicyConfig {
    allowPrefixes = normalized(allowPrefixes, "allowPrefixes");
    denyFragments = normalized(denyFragments, "denyFragments");
  }

  public static CommandPolicyConfig defaults() {
    return new CommandPolicyConfig(
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

  @Override
  public List<String> allowPrefixes() {
    return new ArrayList<>(allowPrefixes);
  }

  @Override
  public List<String> denyFragments() {
    return new ArrayList<>(denyFragments);
  }

  private static List<String> normalized(List<String> values, String field) {
    return List.copyOf(Objects.requireNonNull(values, field + " must not be null")).stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .distinct()
        .toList();
  }
}
