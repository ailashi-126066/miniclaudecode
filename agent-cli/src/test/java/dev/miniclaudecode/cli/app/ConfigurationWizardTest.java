package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.persistence.config.AppConfig;
import dev.miniclaudecode.persistence.config.ConfigLoader;
import dev.miniclaudecode.persistence.config.UserConfigWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class ConfigurationWizardTest {
  @TempDir Path temporaryDirectory;

  @Test
  void masksAndPersistsAPlaintextOpenAiCompatibleKey() throws Exception {
    LineReader reader = (LineReader) Mockito.mock(LineReader.class);
    Terminal terminal = (Terminal) Mockito.mock(Terminal.class);
    Mockito.when(reader.getTerminal()).thenReturn(terminal);
    Mockito.when(terminal.writer())
        .thenReturn(new PrintWriter(OutputStream.nullOutputStream(), true));
    Mockito.when(reader.readLine("Profile name [default]: ")).thenReturn("kedaya");
    Mockito.when(
            reader.readLine(
                "Provider type [1=OpenAI-compatible, 2=Anthropic, 3=Ollama] (default 1): "))
        .thenReturn("1");
    Mockito.when(reader.readLine("Base URL (blank for provider default): "))
        .thenReturn("https://gateway.example/v1");
    Mockito.when(reader.readLine("Model [gpt-4.1]: ")).thenReturn("claude-opus-4-6");
    Mockito.when(
            reader.readLine(
                "API key storage [1=plaintext user config, 2=environment variable] (default 1): "))
        .thenReturn("1");
    Mockito.when(reader.readLine("API Key: ", '*')).thenReturn("secret-value");
    Mockito.when(reader.readLine("Enable thinking summaries? [y/N]: ")).thenReturn("y");
    Path config = this.temporaryDirectory.resolve("home/config.yaml");
    String result = new ConfigurationWizard(config, new UserConfigWriter()).run(reader);
    AppConfig loaded = new ConfigLoader().load(config, Optional.empty());
    Assertions.assertThat(loaded.activeProvider()).isEqualTo("kedaya");
    Assertions.assertThat(loaded.activeProfile().model()).isEqualTo("claude-opus-4-6");
    Assertions.assertThat(loaded.activeProfile().apiKey()).contains("secret-value");
    Assertions.assertThat(loaded.activeProfile().thinking()).isTrue();
    Assertions.assertThat(result)
        .contains(new CharSequence[] {config.toAbsolutePath().toString(), "Restart"});
    Assertions.assertThat(result).doesNotContain(new CharSequence[] {"secret-value"});
    Assertions.assertThat(Files.readString(config))
        .contains(new CharSequence[] {"api-key: \"secret-value\""});
  }
}
