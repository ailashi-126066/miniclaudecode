package dev.miniclaudecode.domain.model;

import java.util.Locale;

/** Model-profile-level contract used to decide when a tool-free response is terminal. */
public enum OutputProtocolType {
  NATURAL_LANGUAGE,
  JSON;

  public static OutputProtocolType parse(String value) {
    if (value == null || value.isBlank()) {
      return NATURAL_LANGUAGE;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "natural", "natural-language", "text" -> NATURAL_LANGUAGE;
      case "json" -> JSON;
      default -> throw new IllegalArgumentException("unsupported output protocol: " + value);
    };
  }

  public String promptInstruction() {
    return switch (this) {
      case NATURAL_LANGUAGE ->
          "When no more tools are needed, answer naturally with a clear final result.";
      case JSON ->
          "When no more tools are needed, return exactly one JSON object with this shape:"
              + " {\"status\":\"completed\",\"final\":\"human-readable result\"}."
              + " Do not wrap it in Markdown.";
    };
  }
}
