package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.persistence.config.ProviderProfile;
import dev.miniclaudecode.providers.ProviderFactory;
import dev.miniclaudecode.providers.ProviderSpec;
import dev.miniclaudecode.providers.ProviderSpec.Type;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow.Publisher;

final class RoutingModelClient implements ModelClient {
  private final Map<String, ProviderProfile> profiles;
  private final Map<String, String> environment;
  private final ProviderFactory factory;
  private final Map<ClientKey, ModelClient> clients = new ConcurrentHashMap<>();

  RoutingModelClient(
      Map<String, ProviderProfile> profiles,
      Map<String, String> environment,
      ProviderFactory factory) {
    this.profiles = Map.copyOf(profiles);
    this.environment = Map.copyOf(environment);
    this.factory = Objects.requireNonNull(factory, "factory must not be null");
  }

  public Publisher<ModelStreamEvent> stream(ModelRequest request) {
    ProviderProfile profile = this.profiles.get(request.providerProfile());
    if (profile == null) {
      throw new IllegalArgumentException("unknown provider profile: " + request.providerProfile());
    } else {
      ClientKey key =
          new ClientKey(
              request.providerProfile(),
              request.modelName(),
              request.thinkingEnabled(),
              request.maxOutputTokens());
      return this.clients
          .computeIfAbsent(
              key, ignored -> this.factory.create(this.specification(profile, request)))
          .stream(request);
    }
  }

  private ProviderSpec specification(ProviderProfile profile, ModelRequest request) {
    return new ProviderSpec(
        Type.valueOf(profile.type().name()),
        profile.baseUrl(),
        profile.resolvedApiKey(this.environment),
        request.modelName(),
        profile.temperature(),
        request.maxOutputTokens(),
        request.thinkingEnabled(),
        profile.timeout(),
        profile.maxRetries());
  }

  private record ClientKey(
      String providerProfile, String modelName, boolean thinking, int maxOutputTokens) {}
}
