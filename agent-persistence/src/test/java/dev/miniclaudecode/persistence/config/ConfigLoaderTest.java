package dev.miniclaudecode.persistence.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
  private Path tempDir;

  @BeforeEach
  void createTestDirectory() throws Exception {
    this.tempDir = Path.of("target", "test-work", UUID.randomUUID().toString());
    Files.createDirectories(this.tempDir);
  }

  @Test
  void mergesUserAndProjectConfigWhileEnvironmentKeyWins() throws Exception {
    Path userConfig = this.tempDir.resolve("user.yaml");
    Files.writeString(
        userConfig,
        "active-provider: work\n"
            + "providers:\n"
            + "  work:\n"
            + "    type: openai-compatible\n"
            + "    base-url: https://gateway.example/v1\n"
            + "    api-key: sk-user-secret\n"
            + "    api-key-env: WORK_API_KEY\n"
            + "    model: user-model\n"
            + "    thinking: true\n");
    Path projectConfig = this.tempDir.resolve("project.yaml");
    Files.writeString(
        projectConfig, "providers:\n  work:\n    model: project-model\n    temperature: 0.1\n");
    AppConfig config = new ConfigLoader().load(userConfig, Optional.of(projectConfig));
    ProviderProfile profile = config.activeProfile();
    Assertions.assertThat(profile.model()).isEqualTo("project-model");
    Assertions.assertThat(profile.temperature()).isEqualTo(0.1);
    Assertions.assertThat(profile.baseUrl())
        .hasValueSatisfying(uri -> Assertions.assertThat(uri).hasHost("gateway.example"));
    Assertions.assertThat(profile.resolvedApiKey(Map.of("WORK_API_KEY", "sk-env-secret")))
        .contains("sk-env-secret");
  }

  @Test
  void rejectsPlaintextKeyInProjectConfig() throws Exception {
    Path userConfig = this.tempDir.resolve("user.yaml");
    Files.writeString(userConfig, "{}");
    Path projectConfig = this.tempDir.resolve("project.yaml");
    Files.writeString(projectConfig, "providers:\n  work:\n    api-key: sk-project-secret\n");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () -> new ConfigLoader().load(userConfig, Optional.of(projectConfig)))
                .isInstanceOf(SecurityException.class))
        .hasMessageContaining("project config")
        .hasMessageContaining("api-key");
  }

  @Test
  void usesDefaultsWhenUserConfigDoesNotExist() {
    AppConfig config =
        new ConfigLoader().load(this.tempDir.resolve("missing.yaml"), Optional.empty());
    Assertions.assertThat(config.providers()).containsKey(config.activeProvider());
    Assertions.assertThat(config.activeProfile().maxOutputTokens()).isPositive();
    Assertions.assertThat(config.activeProfile().timeout().toSeconds()).isPositive();
    Assertions.assertThat(config.embedding().provider()).isEqualTo(EmbeddingConfig.Provider.FAST);
    Assertions.assertThat(config.embedding().dimensions()).isEqualTo(384);
  }

  @Test
  void parsesRemoteEmbeddingConfigurationWithEnvironmentKey() throws Exception {
    Path userConfig = this.tempDir.resolve("user.yaml");
    Files.writeString(
        userConfig,
        "rag:\n"
            + "  embedding:\n"
            + "    provider: remote\n"
            + "    base-url: https://api.example/v1\n"
            + "    api-key-env: EMBED_KEY\n"
            + "    model: text-embedding-3-small\n"
            + "    dimensions: 1536\n"
            + "    timeout-seconds: 10\n");
    EmbeddingConfig embedding = new ConfigLoader().load(userConfig, Optional.empty()).embedding();
    Assertions.assertThat(embedding.provider()).isEqualTo(EmbeddingConfig.Provider.REMOTE);
    Assertions.assertThat(embedding.baseUrl())
        .hasValueSatisfying(uri -> Assertions.assertThat(uri).hasHost("api.example"));
    Assertions.assertThat(embedding.model()).isEqualTo("text-embedding-3-small");
    Assertions.assertThat(embedding.dimensions()).isEqualTo(1536);
    Assertions.assertThat(embedding.timeout()).isEqualTo(java.time.Duration.ofSeconds(10L));
    Assertions.assertThat(embedding.resolvedApiKey(Map.of("EMBED_KEY", "sk-embed")))
        .contains("sk-embed");
  }

  @Test
  void rejectsPlaintextEmbeddingKeyInProjectConfig() throws Exception {
    Path userConfig = this.tempDir.resolve("user.yaml");
    Files.writeString(userConfig, "{}");
    Path projectConfig = this.tempDir.resolve("project.yaml");
    Files.writeString(
        projectConfig, "rag:\n  embedding:\n    api-key: sk-project-embedding-secret\n");
    Assertions.assertThatThrownBy(
            () -> new ConfigLoader().load(userConfig, Optional.of(projectConfig)))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("api-key");
  }

  @Test
  void rejectsSecurityPolicyOverridesInProjectConfig() throws Exception {
    Path userConfig = this.tempDir.resolve("user.yaml");
    Files.writeString(userConfig, "{}");
    Path projectConfig = this.tempDir.resolve("project.yaml");
    Files.writeString(
        projectConfig,
        "security:\n" + "  shell:\n" + "    allowlist-only: false\n" + "    deny-fragments: []\n");

    Assertions.assertThatThrownBy(
            () -> new ConfigLoader().load(userConfig, Optional.of(projectConfig)))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("security");
  }

  @Test
  void commandPolicyAccessorsReturnDefensiveCopies() {
    CommandPolicyConfig policy = CommandPolicyConfig.defaults();

    policy.allowPrefixes().clear();
    policy.denyFragments().clear();

    Assertions.assertThat(policy.allowPrefixes()).isNotEmpty();
    Assertions.assertThat(policy.denyFragments()).isNotEmpty();
  }
}
