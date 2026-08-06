package dev.miniclaudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.cli.commands.SlashCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlashCommandParserTest {

  private final SlashCommandParser parser = new SlashCommandParser();

  @Test
  void parsesEveryApprovedCommand() {
    assertThat(
            List.of(
                    "/help",
                    "/status",
                    "/usage",
                    "/provider openai",
                    "/model gpt-4.1",
                    "/thinking on",
                    "/tools",
                    "/compact",
                    "/checkpoints",
                    "/restore abc123 apply",
                    "/recovery",
                    "/undo",
                    "/redo",
                    "/sessions",
                    "/resume session-1",
                    "/mcp",
                    "/skills",
                    "/config",
                    "/config setup")
                .stream()
                .map(parser::parse)
                .map(SlashCommand::name))
        .containsExactly(
            "help",
            "status",
            "usage",
            "provider",
            "model",
            "thinking",
            "tools",
            "compact",
            "checkpoints",
            "restore",
            "recovery",
            "undo",
            "redo",
            "sessions",
            "resume",
            "mcp",
            "skills",
            "config",
            "config");
  }

  @Test
  void supportsProviderAndModelQueriesWithoutChangingTheirValues() {
    assertThat(parser.parse("/provider"))
        .isEqualTo(new SlashCommand.Provider(java.util.Optional.empty()));
    assertThat(parser.parse("/model"))
        .isEqualTo(new SlashCommand.Model(java.util.Optional.empty()));
    assertThat(parser.parse("/config setup")).isEqualTo(new SlashCommand.Config(true));
  }

  @Test
  void rejectsInternalRagCommandsAndInvalidThinkingValues() {
    assertThatThrownBy(() -> parser.parse("/index"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown");
    assertThatThrownBy(() -> parser.parse("/rag stats"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parser.parse("/thinking maybe")).hasMessageContaining("on|off");
    assertThatThrownBy(() -> parser.parse("/undo operation-1"))
        .hasMessageContaining("does not accept arguments");
  }
}
