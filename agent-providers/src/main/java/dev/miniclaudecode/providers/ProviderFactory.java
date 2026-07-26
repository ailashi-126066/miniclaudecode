package dev.miniclaudecode.providers;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.providers.anthropic.AnthropicModelClient;
import dev.miniclaudecode.providers.ollama.OllamaModelClient;
import dev.miniclaudecode.providers.openai.OpenAiCompatibleModelClient;
import java.util.Objects;

public final class ProviderFactory {

  public ModelClient create(ProviderSpec spec) {
    Objects.requireNonNull(spec, "spec must not be null");
    return switch (spec.type()) {
      case ANTHROPIC -> new AnthropicModelClient(spec);
      case OPENAI_COMPATIBLE -> new OpenAiCompatibleModelClient(spec);
      case OLLAMA -> new OllamaModelClient(spec);
    };
  }
}
