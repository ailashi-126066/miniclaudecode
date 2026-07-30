package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import dev.miniclaudecode.persistence.event.JsonlEventStore;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSearchToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void searchesPriorRequestsAndFinalAnswersWithoutASecondMemoryStore() throws Exception {
    Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    UserDataLayout layout = UserDataLayout.forHome(temporaryDirectory.resolve("home"));
    Path root = layout.sessionWorkspaceRoot(workspace).resolve("events");
    JsonlEventStore events = new JsonlEventStore(root, new SecretRedactor(), Set.of());
    SessionId prior = SessionId.of("prior-session");
    events.append(
        AgentEvent.create(
            prior,
            TurnId.of(2),
            AgentEventType.USER_MESSAGE,
            Map.of("text", "Fix Maven formatting"),
            Clock.systemUTC()));
    events.append(
        AgentEvent.create(
            prior,
            TurnId.of(2),
            AgentEventType.TURN_FINAL,
            Map.of("text", "Spotless passed"),
            Clock.systemUTC()));

    var result =
        new SessionSearchTool(workspace, layout, Set.of())
            .execute(
                new ToolCall("search", "session:search", "{\"query\":\"Maven\"}"),
                new ToolContext(
                    SessionId.of("current"), TurnId.of(1), workspace, ignored -> {}, Map.of()))
            .toCompletableFuture()
            .get();

    assertThat(result.summary()).contains("session=prior-session", "Fix Maven formatting");
  }
}
