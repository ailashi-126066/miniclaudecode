package dev.miniclaudecode.persistence.config;

import java.util.Map;
import java.util.Objects;

public record AppConfig(
    Map<String, ProviderProfile> providers, String activeProvider, EmbeddingConfig embedding) {
  public AppConfig(
      Map<String, ProviderProfile> providers, String activeProvider, EmbeddingConfig embedding) {
    providers = Map.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
    Objects.requireNonNull(embedding, "embedding must not be null");
    if (providers.isEmpty()) {
      throw new IllegalArgumentException("at least one provider must be configured");
    } else if (activeProvider != null && !activeProvider.isBlank()) {
      activeProvider = activeProvider.trim();
      if (!providers.containsKey(activeProvider)) {
        throw new IllegalArgumentException(
            "activeProvider does not reference a configured provider: " + activeProvider);
      } else {
        this.providers = providers;
        this.activeProvider = activeProvider;
        this.embedding = embedding;
      }
    } else {
      throw new IllegalArgumentException("activeProvider must not be blank");
    }
  }

  public ProviderProfile activeProfile() {
    return this.providers.get(this.activeProvider);
  }
}
