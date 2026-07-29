package dev.miniclaudecode.providers.ollama;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.providers.ModelProviderPlugin;
import dev.miniclaudecode.providers.ProviderSpec;

public final class OllamaProviderPlugin implements ModelProviderPlugin {
  @Override
  public ProviderSpec.Type type() {
    return ProviderSpec.Type.OLLAMA;
  }

  @Override
  public ModelClient create(ProviderSpec spec) {
    return new OllamaModelClient(spec);
  }
}
