package dev.miniclaudecode.persistence.config;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SecretRedactor {
  private static final String MASK = "***";
  private static final Pattern AUTHORIZATION =
      Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)([^\\s,;]+)");
  private static final Pattern API_KEY =
      Pattern.compile("(?i)((?:x-)?api[-_]?key\\s*[:=]\\s*)([^\\s,;&]+)");
  private static final Pattern SENSITIVE_QUERY =
      Pattern.compile("(?i)([?&](?:api[-_]?key|access[-_]?token|token|key)=)([^&\\s]+)");

  public String redact(String value, Set<String> knownSecrets) {
    if (value != null && !value.isEmpty()) {
      Objects.requireNonNull(knownSecrets, "knownSecrets must not be null");
      String redacted = maskPattern(AUTHORIZATION, value);
      redacted = maskPattern(API_KEY, redacted);
      redacted = maskPattern(SENSITIVE_QUERY, redacted);

      for (String secret :
          knownSecrets.stream()
              .filter(Objects::nonNull)
              .filter(secretx -> !secretx.isEmpty())
              .sorted(Comparator.comparingInt(String::length).reversed())
              .toList()) {
        redacted = redacted.replace(secret, "***");
      }

      return redacted;
    } else {
      return value;
    }
  }

  private static String maskPattern(Pattern pattern, String value) {
    Matcher matcher = pattern.matcher(value);
    return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "***"));
  }
}
