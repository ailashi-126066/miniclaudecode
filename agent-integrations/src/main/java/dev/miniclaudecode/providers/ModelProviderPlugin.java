package dev.miniclaudecode.providers;

import dev.miniclaudecode.domain.model.ModelClient;

/** Service-provider boundary for one model transport implementation. */
public interface ModelProviderPlugin {
  ProviderSpec.Type type();

  ModelClient create(ProviderSpec spec);
}
