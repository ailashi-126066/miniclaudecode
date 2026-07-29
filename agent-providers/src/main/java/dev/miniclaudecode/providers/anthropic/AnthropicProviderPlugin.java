package dev.miniclaudecode.providers.anthropic;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.providers.ModelProviderPlugin;
import dev.miniclaudecode.providers.ProviderSpec;

public final class AnthropicProviderPlugin implements ModelProviderPlugin {
  @Override
  public ProviderSpec.Type type() {
    return ProviderSpec.Type.ANTHROPIC;
  }

  @Override
  public ModelClient create(ProviderSpec spec) {
    return new AnthropicModelClient(spec);
  }
}
