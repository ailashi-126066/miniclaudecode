package dev.miniclaudecode.persistence.config;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration for the code-index embedding provider.
 *
 * <p>{@code FAST} is the default: the offline hashing model — zero dependencies, zero downloads,
 * reproducible, good enough for demos and CI. {@code REMOTE} calls an OpenAI-compatible {@code
 * /v1/embeddings} endpoint for real neural embeddings; it must declare the vector {@code
 * dimensions} up front because the Lucene vector field needs the dimension before the first request
 * is ever made.
 */
public record EmbeddingConfig(
    EmbeddingConfig.Provider provider,
    Optional<URI> baseUrl,
    Optional<String> apiKey,
    Optional<String> apiKeyEnv,
    String model,
    int dimensions,
    Duration timeout) {

  public EmbeddingConfig {
    Objects.requireNonNull(provider, "provider must not be null");
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    baseUrl.ifPresent(EmbeddingConfig::validateBaseUrl);
    apiKey = normalizeSecret(apiKey, "apiKey");
    apiKeyEnv = normalizeSecret(apiKeyEnv, "apiKeyEnv");
    model = model == null ? "" : model.trim();
    if (dimensions < 32) {
      throw new IllegalArgumentException("embedding dimensions must be at least 32");
    }
    Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (provider == Provider.REMOTE) {
      if (baseUrl.isEmpty()) {
        throw new IllegalArgumentException("remote embedding provider requires base-url");
      }
      if (model.isBlank()) {
        throw new IllegalArgumentException("remote embedding provider requires model");
      }
    }
  }

  public static EmbeddingConfig fastDefault() {
    return new EmbeddingConfig(
        Provider.FAST,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        "",
        384,
        Duration.ofSeconds(30L));
  }

  public Optional<String> resolvedApiKey(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment must not be null");
    Optional<String> environmentValue =
        this.apiKeyEnv.flatMap(
            variable ->
                Optional.ofNullable(environment.get(variable))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty()));
    return environmentValue.or(this::apiKey);
  }

  private static Optional<String> normalizeSecret(Optional<String> value, String field) {
    return Objects.requireNonNull(value, field + " must not be null")
        .map(String::trim)
        .filter(text -> !text.isEmpty());
  }

  private static void validateBaseUrl(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("embedding base-url must use http or https");
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("embedding base-url must include a host");
    }
  }

  public enum Provider {
    FAST,
    REMOTE;

    public static EmbeddingConfig.Provider parse(String value) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("embedding provider must not be blank");
      }
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "fast" -> FAST;
        case "remote" -> REMOTE;
        default -> throw new IllegalArgumentException("unsupported embedding provider: " + value);
      };
    }
  }
}
