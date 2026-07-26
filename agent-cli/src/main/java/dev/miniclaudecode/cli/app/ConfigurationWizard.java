package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.persistence.config.ProviderProfile;
import dev.miniclaudecode.persistence.config.ProviderProfile.Type;
import dev.miniclaudecode.persistence.config.UserConfigWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jline.reader.LineReader;

final class ConfigurationWizard {
  private static final Character MASK = '*';
  private final Path configFile;
  private final UserConfigWriter writer;

  ConfigurationWizard(Path configFile, UserConfigWriter writer) {
    this.configFile =
        Objects.requireNonNull(configFile, "configFile must not be null")
            .toAbsolutePath()
            .normalize();
    this.writer = Objects.requireNonNull(writer, "writer must not be null");
  }

  String run(LineReader reader) {
    Objects.requireNonNull(reader, "reader must not be null");
    reader.getTerminal().writer().println("MiniClaudeCode provider setup");
    reader
        .getTerminal()
        .writer()
        .println(
            "API keys stored in the user config are plaintext. Input is masked only on screen.");
    reader.getTerminal().flush();
    String profileName = answer(reader, "Profile name [default]: ", "default");
    Type type = providerType(answer(reader, typePrompt(), "1"));
    Optional<URI> baseUrl = baseUrl(reader, type);
    String model = answer(reader, "Model [" + defaultModel(type) + "]: ", defaultModel(type));
    ConfigurationWizard.Credentials credentials = credentials(reader, type);
    boolean thinking = yes(answer(reader, "Enable thinking summaries? [y/N]: ", "n"));
    ProviderProfile profile =
        new ProviderProfile(
            type,
            baseUrl,
            credentials.apiKey(),
            credentials.apiKeyEnvironment(),
            model,
            0.2,
            8192,
            thinking,
            Duration.ofSeconds(120L),
            3);
    this.writer.upsertProvider(this.configFile, profileName, profile, true);
    String storage =
        credentials.apiKey().isPresent()
            ? "API key saved as plaintext in the user config."
            : credentials
                .apiKeyEnvironment()
                .map(value -> "API key environment: " + value)
                .orElse("No API key required.");
    return "Configuration saved to "
        + this.configFile
        + System.lineSeparator()
        + storage
        + System.lineSeparator()
        + "Restart MiniClaudeCode to apply the new active provider.";
  }

  private static Type providerType(String selection) {
    String var1 = selection.trim().toLowerCase(Locale.ROOT);

    return switch (var1) {
      case "1", "openai-compatible", "openai" -> Type.OPENAI_COMPATIBLE;
      case "2", "anthropic" -> Type.ANTHROPIC;
      case "3", "ollama" -> Type.OLLAMA;
      default -> throw new IllegalArgumentException("choose provider type 1, 2 or 3");
    };
  }

  private static Optional<URI> baseUrl(LineReader reader, Type type) {
    String defaultValue = type == Type.OLLAMA ? "http://localhost:11434" : "";
    String prompt =
        defaultValue.isEmpty()
            ? "Base URL (blank for provider default): "
            : "Base URL [" + defaultValue + "]: ";
    String value = answer(reader, prompt, defaultValue);
    return value.isEmpty() ? Optional.empty() : Optional.of(URI.create(value));
  }

  private static ConfigurationWizard.Credentials credentials(LineReader reader, Type type) {
    if (type == Type.OLLAMA) {
      return new ConfigurationWizard.Credentials(Optional.empty(), Optional.empty());
    } else {
      String storage =
          answer(
              reader,
              "API key storage [1=plaintext user config, 2=environment variable] (default 1): ",
              "1");
      if (storage.equals("1")) {
        String key = readSecret(reader);
        if (key != null && !key.isBlank()) {
          return new ConfigurationWizard.Credentials(Optional.of(key.trim()), Optional.empty());
        } else {
          throw new IllegalArgumentException("API Key must not be blank");
        }
      } else if (storage.equals("2")) {
        String defaultVariable = type == Type.ANTHROPIC ? "ANTHROPIC_API_KEY" : "OPENAI_API_KEY";
        return new ConfigurationWizard.Credentials(
            Optional.empty(),
            Optional.of(
                answer(
                    reader, "Environment variable [" + defaultVariable + "]: ", defaultVariable)));
      } else {
        throw new IllegalArgumentException("choose API key storage 1 or 2");
      }
    }
  }

  private static String answer(LineReader reader, String prompt, String defaultValue) {
    String value = reader.readLine(prompt);
    return value != null && !value.isBlank() ? value.trim() : defaultValue;
  }

  private static String readSecret(LineReader reader) {
    String terminalType = reader.getTerminal().getType();
    return terminalType != null && terminalType.equalsIgnoreCase("dumb")
        ? reader.readLine("API Key: ")
        : reader.readLine("API Key: ", MASK);
  }

  private static String typePrompt() {
    return "Provider type [1=OpenAI-compatible, 2=Anthropic, 3=Ollama] (default 1): ";
  }

  private static String defaultModel(Type type) {
    return switch (type) {
      case OPENAI_COMPATIBLE -> "gpt-4.1";
      case ANTHROPIC -> "claude-sonnet-4-5";
      case OLLAMA -> "qwen2.5-coder:7b";
      default -> throw new MatchException(null, null);
    };
  }

  private static boolean yes(String value) {
    return value.equalsIgnoreCase("y") || value.equalsIgnoreCase("yes");
  }

  private static record Credentials(Optional<String> apiKey, Optional<String> apiKeyEnvironment) {
    private Credentials(Optional<String> apiKey, Optional<String> apiKeyEnvironment) {
      Objects.requireNonNull(apiKey);
      Objects.requireNonNull(apiKeyEnvironment);
      this.apiKey = apiKey;
      this.apiKeyEnvironment = apiKeyEnvironment;
    }
  }
}
