package dev.miniclaudecode.persistence.config;

import dev.miniclaudecode.domain.model.OutputProtocolType;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ProviderProfile(
    ProviderProfile.Type type,
    Optional<URI> baseUrl,
    Optional<String> apiKey,
    Optional<String> apiKeyEnv,
    String model,
    double temperature,
    int maxOutputTokens,
    boolean thinking,
    Duration timeout,
    int maxRetries,
    OutputProtocolType outputProtocol,
    int maxOutputRepairs) {
  public ProviderProfile(
      ProviderProfile.Type type,
      Optional<URI> baseUrl,
      Optional<String> apiKey,
      Optional<String> apiKeyEnv,
      String model,
      double temperature,
      int maxOutputTokens,
      boolean thinking,
      Duration timeout,
      int maxRetries) {
    this(
        type,
        baseUrl,
        apiKey,
        apiKeyEnv,
        model,
        temperature,
        maxOutputTokens,
        thinking,
        timeout,
        maxRetries,
        OutputProtocolType.NATURAL_LANGUAGE,
        1);
  }

  public ProviderProfile(
      ProviderProfile.Type type,
      Optional<URI> baseUrl,
      Optional<String> apiKey,
      Optional<String> apiKeyEnv,
      String model,
      double temperature,
      int maxOutputTokens,
      boolean thinking,
      Duration timeout,
      int maxRetries,
      OutputProtocolType outputProtocol,
      int maxOutputRepairs) {
    Objects.requireNonNull(type, "type must not be null");
    baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    baseUrl.ifPresent(ProviderProfile::validateBaseUrl);
    apiKey = normalizeSecret(apiKey, "apiKey");
    apiKeyEnv = normalizeSecret(apiKeyEnv, "apiKeyEnv");
    if (model != null && !model.isBlank()) {
      model = model.trim();
      if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 2.0) {
        throw new IllegalArgumentException("temperature must be between 0 and 2");
      } else if (maxOutputTokens < 1) {
        throw new IllegalArgumentException("maxOutputTokens must be greater than zero");
      } else {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
          throw new IllegalArgumentException("timeout must be positive");
        } else if (maxRetries >= 0 && maxRetries <= 10) {
          Objects.requireNonNull(outputProtocol, "outputProtocol must not be null");
          if (maxOutputRepairs < 0 || maxOutputRepairs > 5) {
            throw new IllegalArgumentException("maxOutputRepairs must be between 0 and 5");
          }
          this.type = type;
          this.baseUrl = baseUrl;
          this.apiKey = apiKey;
          this.apiKeyEnv = apiKeyEnv;
          this.model = model;
          this.temperature = temperature;
          this.maxOutputTokens = maxOutputTokens;
          this.thinking = thinking;
          this.timeout = timeout;
          this.maxRetries = maxRetries;
          this.outputProtocol = outputProtocol;
          this.maxOutputRepairs = maxOutputRepairs;
        } else {
          throw new IllegalArgumentException("maxRetries must be between 0 and 10");
        }
      }
    } else {
      throw new IllegalArgumentException("model must not be blank");
    }
  }

  public Optional<String> resolvedApiKey(Map<String, String> environment) {
    Objects.requireNonNull(environment, "environment must not be null");
    Optional<String> environmentValue =
        this.apiKeyEnv.flatMap(
            variable ->
                Optional.ofNullable(environment.get(variable))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty()));
    return environmentValue.or(() -> this.apiKey);
  }

  private static Optional<String> normalizeSecret(Optional<String> value, String field) {
    return Objects.requireNonNull(value, field + " must not be null")
        .map(String::trim)
        .filter(text -> !text.isEmpty());
  }

  private static void validateBaseUrl(URI uri) {
    String scheme = uri.getScheme();
    if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      if (uri.getHost() == null) {
        throw new IllegalArgumentException("baseUrl must include a host");
      }
    } else {
      throw new IllegalArgumentException("baseUrl must use http or https");
    }
  }

  public static enum Type {
    ANTHROPIC,
    OPENAI_COMPATIBLE,
    OLLAMA;

    public static ProviderProfile.Type parse(String value) {
      if (value != null && !value.isBlank()) {
        String var1 = value.trim().toLowerCase(Locale.ROOT);

        return switch (var1) {
          case "anthropic" -> ANTHROPIC;
          case "openai-compatible" -> OPENAI_COMPATIBLE;
          case "ollama" -> OLLAMA;
          default -> throw new IllegalArgumentException("unsupported provider type: " + value);
        };
      } else {
        throw new IllegalArgumentException("provider type must not be blank");
      }
    }
  }
}
