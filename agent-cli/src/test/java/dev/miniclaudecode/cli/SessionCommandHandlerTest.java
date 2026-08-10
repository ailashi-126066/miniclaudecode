package dev.miniclaudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.cli.commands.SlashCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionCommandHandlerTest {

  @Test
  void changesProviderModelAndThinkingWithoutExposingSecretsInStatus() {
    AtomicBoolean compacted = new AtomicBoolean();
    SessionCommandHandler handler =
        new SessionCommandHandler(
                Map.of("anthropic", List.of("claude"), "openai", List.of("gpt-4.1")),
                "anthropic",
                "claude",
                true,
                List.of("workspace:read", "shell:run"),
                () -> "Session: session-1",
                () -> "Prompt cache hit: 75.0%",
                () -> "session-1",
                () -> "(no MCP servers)",
                () -> "(no skills)",
                () -> "checkpoint",
                () -> compacted.set(true),
                ignored -> {},
                ignored -> "restore",
                () -> "undo",
                () -> "redo",
                Path.of("config.yaml"))
            .withPlanAndMemory(ignored -> "Plan ACTIVE", ignored -> "Memory PENDING");

    handler.execute(new SlashCommand.Provider(Optional.of("openai")));
    handler.execute(new SlashCommand.Thinking(false));
    String status = handler.execute(new SlashCommand.Status());
    String usage = handler.execute(new SlashCommand.Usage());
    handler.execute(new SlashCommand.Compact());

    assertThat(handler.activeProvider()).isEqualTo("openai");
    assertThat(handler.activeModel()).isEqualTo("gpt-4.1");
    assertThat(status).contains("Thinking: off", "Session: session-1").doesNotContain("api-key");
    assertThat(usage).contains("Prompt cache hit: 75.0%");
    assertThat(compacted).isTrue();
    assertThat(handler.execute(new SlashCommand.Recovery())).isEqualTo("checkpoint");
    assertThat(handler.execute(new SlashCommand.PlanView("current", java.util.Optional.empty())))
        .isEqualTo("Plan ACTIVE");
    assertThat(handler.execute(new SlashCommand.Memory("pending", java.util.Optional.empty())))
        .isEqualTo("Memory PENDING");
  }
}
