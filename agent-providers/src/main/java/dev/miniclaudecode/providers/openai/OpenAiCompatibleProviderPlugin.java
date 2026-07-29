package dev.miniclaudecode.providers.openai;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.providers.ModelProviderPlugin;
import dev.miniclaudecode.providers.ProviderSpec;

public final class OpenAiCompatibleProviderPlugin implements ModelProviderPlugin {
  @Override
  public ProviderSpec.Type type() {
    return ProviderSpec.Type.OPENAI_COMPATIBLE;
  }

  @Override
  public ModelClient create(ProviderSpec spec) {
    return new OpenAiCompatibleModelClient(spec);
  }
}
