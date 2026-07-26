package dev.miniclaudecode.providers;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ProviderSpec(
    Type type,
    Optional<URI> baseUrl,
    Optional<String> apiKey,
    String model,
    double temperature,
    int maxOutputTokens,
    boolean thinking,
    Duration timeout,
    int maxRetries) {

  public enum Type {
    ANTHROPIC,
    OPENAI_COMPATIBLE,
    OLLAMA
  }

  public ProviderSpec {
    Objects.requireNonNull(type, "type must not be null");
    baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    baseUrl.ifPresent(ProviderSpec::validateBaseUrl);
    apiKey = normalize(apiKey, "apiKey");
    model = requireText(model, "model");
    if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
      throw new IllegalArgumentException("temperature must be between 0 and 2");
    }
    if (maxOutputTokens < 1) {
      throw new IllegalArgumentException("maxOutputTokens must be greater than zero");
    }
    if (type == Type.ANTHROPIC && thinking && maxOutputTokens <= 1024) {
      throw new IllegalArgumentException(
          "Anthropic thinking requires maxOutputTokens greater than 1024");
    }
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (maxRetries < 0 || maxRetries > 10) {
      throw new IllegalArgumentException("maxRetries must be between 0 and 10");
    }
    if (type != Type.OLLAMA && apiKey.isEmpty()) {
      throw new IllegalArgumentException("apiKey is required for " + type);
    }
    if (type == Type.OLLAMA && baseUrl.isEmpty()) {
      throw new IllegalArgumentException("baseUrl is required for OLLAMA");
    }
  }

  private static Optional<String> normalize(Optional<String> value, String field) {
    return Objects.requireNonNull(value, field + " must not be null")
        .map(String::trim)
        .filter(text -> !text.isEmpty());
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  private static void validateBaseUrl(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("baseUrl must use http or https");
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("baseUrl must include a host");
    }
  }
}
