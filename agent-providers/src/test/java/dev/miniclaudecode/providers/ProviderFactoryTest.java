package dev.miniclaudecode.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.providers.anthropic.AnthropicModelClient;
import dev.miniclaudecode.providers.ollama.OllamaModelClient;
import dev.miniclaudecode.providers.openai.OpenAiCompatibleModelClient;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderFactoryTest {

  private final ProviderFactory factory = new ProviderFactory();

  @Test
  void createsAllSupportedProviderAdapters() {
    assertThat(factory.create(spec(ProviderSpec.Type.ANTHROPIC)))
        .isInstanceOf(AnthropicModelClient.class);
    assertThat(factory.create(spec(ProviderSpec.Type.OPENAI_COMPATIBLE)))
        .isInstanceOf(OpenAiCompatibleModelClient.class);
    assertThat(factory.create(spec(ProviderSpec.Type.OLLAMA)))
        .isInstanceOf(OllamaModelClient.class);
  }

  @Test
  void validatesProviderCredentialsAndUrls() {
    assertThatThrownBy(
            () ->
                new ProviderSpec(
                    ProviderSpec.Type.ANTHROPIC,
                    Optional.empty(),
                    Optional.empty(),
                    "claude",
                    0.2,
                    1024,
                    false,
                    Duration.ofSeconds(10),
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("apiKey");
    assertThatThrownBy(
            () ->
                new ProviderSpec(
                    ProviderSpec.Type.OLLAMA,
                    Optional.of(URI.create("file:///tmp/ollama")),
                    Optional.empty(),
                    "qwen",
                    0.2,
                    1024,
                    false,
                    Duration.ofSeconds(10),
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http");
    assertThatThrownBy(
            () ->
                new ProviderSpec(
                    ProviderSpec.Type.ANTHROPIC,
                    Optional.empty(),
                    Optional.of("key"),
                    "claude",
                    0.2,
                    1024,
                    true,
                    Duration.ofSeconds(10),
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("greater than 1024");
  }

  private static ProviderSpec spec(ProviderSpec.Type type) {
    boolean ollama = type == ProviderSpec.Type.OLLAMA;
    return new ProviderSpec(
        type,
        Optional.of(URI.create(ollama ? "http://localhost:11434" : "https://api.example/v1")),
        ollama ? Optional.empty() : Optional.of("test-key"),
        ollama ? "qwen3:8b" : "test-model",
        0.2,
        2048,
        false,
        Duration.ofSeconds(30),
        1);
  }
}
