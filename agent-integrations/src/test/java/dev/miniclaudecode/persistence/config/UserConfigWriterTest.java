package dev.miniclaudecode.persistence.config;

import dev.miniclaudecode.persistence.config.ProviderProfile.Type;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserConfigWriterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void upsertsActiveProviderWithoutDiscardingMcpConfiguration() throws Exception {
    Path config = this.temporaryDirectory.resolve("nested/config.yaml");
    Files.createDirectories(config.getParent());
    Files.writeString(
        config,
        "active-provider: ollama\n"
            + "providers:\n"
            + "  ollama:\n"
            + "    type: ollama\n"
            + "    base-url: http://localhost:11434\n"
            + "    model: local\n"
            + "mcp:\n"
            + "  servers:\n"
            + "    demo:\n"
            + "      transport: stdio\n");
    ProviderProfile profile =
        new ProviderProfile(
            Type.OPENAI_COMPATIBLE,
            Optional.of(URI.create("https://gateway.example/v1")),
            Optional.of("secret-value"),
            Optional.empty(),
            "remote-model",
            0.2,
            4096,
            true,
            Duration.ofSeconds(90L),
            2);
    new UserConfigWriter().upsertProvider(config, "work", profile, true);
    String yaml = Files.readString(config);
    AppConfig loaded = new ConfigLoader().load(config, Optional.empty());
    Assertions.assertThat(loaded.activeProvider()).isEqualTo("work");
    Assertions.assertThat(loaded.activeProfile().apiKey()).contains("secret-value");
    Assertions.assertThat(yaml)
        .contains(new CharSequence[] {"mcp:", "demo:", "api-key: \"secret-value\""});
  }
}
