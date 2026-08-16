package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.TurnEvent;
import dev.miniclaudecode.cli.app.ApplicationSession.TurnSelection;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.config.ProviderProfile;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import dev.miniclaudecode.persistence.event.JsonlEventStore;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionResumeE2ETest {
  @TempDir Path temporaryDirectory;

  @Test
  void reconstructsConversationHistoryFromJsonlAfterRestart() throws Exception {
    Path workspace = this.temporaryDirectory.resolve("workspace");
    Files.createDirectories(workspace);
    UserDataLayout layout = UserDataLayout.forHome(this.temporaryDirectory.resolve("home"));
    WorkspaceComponents firstComponents =
        WorkspaceComponents.create(workspace, layout, Map.of(), Optional.of("first"));
    TurnSelection selection = selection(firstComponents);
    ApplicationSession first =
        new ApplicationSession(firstComponents, () -> selection, Clock.systemUTC());
    List<TurnEvent> rendered = new ArrayList<>();
    first
        .start("first prompt", new CancellationToken(), rendered::add)
        .toCompletableFuture()
        .join();
    String sessionId =
        first.status().lines().findFirst().orElseThrow().substring("Session: ".length());
    WorkspaceComponents restartedComponents =
        WorkspaceComponents.create(workspace, layout, Map.of(), Optional.of("second"));
    AtomicReference<TurnSelection> restartedSelection =
        new AtomicReference<>(selection(restartedComponents));
    ApplicationSession restarted =
        new ApplicationSession(restartedComponents, restartedSelection::get, Clock.systemUTC());
    restarted.switchTo(sessionId);
    restarted
        .start("second prompt", new CancellationToken(), rendered::add)
        .toCompletableFuture()
        .join();
    Assertions.assertThat(restarted.status())
        .contains(new CharSequence[] {"Session: " + sessionId, "Turn: 3"});
    Path eventFile =
        layout.sessionWorkspaceRoot(workspace).resolve("events").resolve(sessionId + ".jsonl");
    Assertions.assertThat(Files.readString(eventFile))
        .contains(new CharSequence[] {"first prompt", "second prompt", "first", "second"});
  }

  @Test
  void restoresTaskProgressAndAPendingApprovalFromAuditEvents() throws Exception {
    Path workspace = this.temporaryDirectory.resolve("workspace");
    Files.createDirectories(workspace);
    UserDataLayout layout = UserDataLayout.forHome(this.temporaryDirectory.resolve("home"));
    SessionId sessionId = SessionId.of("durable-session");
    TurnId turnId = TurnId.of(3L);
    JsonlEventStore events =
        new JsonlEventStore(
            layout.sessionWorkspaceRoot(workspace).resolve("events"),
            new SecretRedactor(),
            Set.of());
    events.append(
        new AgentEvent(
            UUID.randomUUID(),
            1,
            sessionId,
            turnId,
            Instant.parse("2026-07-22T00:00:00Z"),
            AgentEventType.USER_MESSAGE,
            Map.of("text", "Fix App.java")));
    events.append(
        AgentEvent.create(
            sessionId,
            turnId,
            AgentEventType.TASK_UPDATED,
            Map.of(
                "items",
                List.of(
                    Map.of("id", "1", "content", "inspect", "status", "done"),
                    Map.of("id", "2", "content", "verify", "status", "in_progress"))),
            Clock.systemUTC()));
    String approvalId = "aabbccdd-1122-3344-5566-778899001122";
    events.append(
        AgentEvent.create(
            sessionId,
            turnId,
            AgentEventType.APPROVAL_REQUESTED,
            Map.ofEntries(
                Map.entry("approvalId", approvalId),
                Map.entry("toolCallId", "edit-1"),
                Map.entry("tool", "workspace:edit"),
                Map.entry("arguments", "{\"path\":\"App.java\"}"),
                Map.entry("risk", "MEDIUM"),
                Map.entry("target", "App.java"),
                Map.entry("reason", "Apply diff"),
                Map.entry("beforeHash", "before"),
                Map.entry("diffHash", "diff"),
                Map.entry("requestedAt", "2026-07-22T00:00:01Z"),
                Map.entry("preview", "--- App.java\n+++ App.java")),
            Clock.systemUTC()));
    WorkspaceComponents components =
        WorkspaceComponents.create(workspace, layout, Map.of(), Optional.of("done"));
    ApplicationSession session =
        new ApplicationSession(components, () -> selection(components), Clock.systemUTC());
    session.switchTo(sessionId.value());
    Assertions.assertThat(session.status()).contains(new CharSequence[] {"Turn: 4", "Tasks: 1/2"});
    Assertions.assertThat(session.pendingApproval())
        .get()
        .extracting(value -> value.approvalId().toString())
        .isEqualTo(approvalId);
    Assertions.assertThat(session.pendingApprovalPreview()).contains("--- App.java\n+++ App.java");
  }

  private static TurnSelection selection(WorkspaceComponents components) {
    ProviderProfile profile = components.config().activeProfile();
    return new TurnSelection(
        components.config().activeProvider(), profile.model(), profile.thinking());
  }
}
