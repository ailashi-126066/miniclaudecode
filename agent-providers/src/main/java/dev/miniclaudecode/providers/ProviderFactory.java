package dev.miniclaudecode.providers;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.providers.anthropic.AnthropicProviderPlugin;
import dev.miniclaudecode.providers.ollama.OllamaProviderPlugin;
import dev.miniclaudecode.providers.openai.OpenAiCompatibleProviderPlugin;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

public final class ProviderFactory {
  private final Map<ProviderSpec.Type, ModelProviderPlugin> plugins;

  public ProviderFactory() {
    this(discover());
  }

  ProviderFactory(List<ModelProviderPlugin> plugins) {
    EnumMap<ProviderSpec.Type, ModelProviderPlugin> registered =
        new EnumMap<>(ProviderSpec.Type.class);
    for (ModelProviderPlugin plugin : plugins) {
      ModelProviderPlugin previous = registered.putIfAbsent(plugin.type(), plugin);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate model provider plugin: " + plugin.type());
      }
    }
    this.plugins = Map.copyOf(registered);
  }

  public ModelClient create(ProviderSpec spec) {
    Objects.requireNonNull(spec, "spec must not be null");
    ModelProviderPlugin plugin = this.plugins.get(spec.type());
    if (plugin == null) {
      throw new IllegalArgumentException("no model provider plugin registered for " + spec.type());
    }
    return plugin.create(spec);
  }

  public List<ProviderSpec.Type> supportedTypes() {
    return this.plugins.keySet().stream().sorted().toList();
  }

  private static List<ModelProviderPlugin> discover() {
    List<ModelProviderPlugin> discovered =
        ServiceLoader.load(ModelProviderPlugin.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList();
    return discovered.isEmpty()
        ? List.of(
            new AnthropicProviderPlugin(),
            new OpenAiCompatibleProviderPlugin(),
            new OllamaProviderPlugin())
        : discovered;
  }
}
