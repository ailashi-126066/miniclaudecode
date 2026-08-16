package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.cli.app.BackgroundAgentManager.Mode;
import dev.miniclaudecode.cli.app.BackgroundAgentManager.RunResult;
import dev.miniclaudecode.cli.app.BackgroundAgentManager.TaskSpec;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundAgentManagerTest {
  @TempDir Path temporary;

  @Test
  void startsWithoutBlockingNotifiesAndRestoresCompletedTasks() {
    CountDownLatch release = new CountDownLatch(1);
    List<AgentEvent> events = new ArrayList<>();
    BackgroundAgentManager manager =
        manager(
            (spec, parent, cancellation) -> {
              try {
                release.await();
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
              }
              return new RunResult(AgentStatus.COMPLETED, "finished " + spec.task(), 2, 3);
            });
    ToolContext context = context(events);

    String id =
        manager.start(
            new TaskSpec("inspect", "explore", 4, Mode.FORK, "parent snapshot", 0), context);

    assertThat(id).startsWith("bg-");
    assertThat(manager.status(id).status().terminal()).isFalse();
    release.countDown();
    assertThat(manager.waitFor(id, 5_000).status())
        .isEqualTo(BackgroundAgentManager.TaskStatus.COMPLETED);
    assertThat(manager.drainNotifications("session-1")).singleElement().asString().contains(id);
    assertThat(events)
        .extracting(AgentEvent::type)
        .contains(
            dev.miniclaudecode.domain.event.AgentEventType.BACKGROUND_STARTED,
            dev.miniclaudecode.domain.event.AgentEventType.BACKGROUND_COMPLETED);
    manager.close();

    BackgroundAgentManager restored =
        manager(
            (spec, parent, cancellation) -> {
              throw new AssertionError("completed tasks must not rerun");
            });
    assertThat(restored.status(id).resultSummary()).contains("finished inspect");
    restored.close();
  }

  @Test
  void cancelsRunningTask() {
    BackgroundAgentManager manager =
        manager(
            (spec, parent, cancellation) -> {
              while (!cancellation.isCancellationRequested()) Thread.onSpinWait();
              return new RunResult(AgentStatus.CANCELLED, "cancelled", 0, 0);
            });
    String id =
        manager.start(
            new TaskSpec("wait", "review", 2, Mode.ISOLATED, "", 0), context(new ArrayList<>()));

    assertThat(manager.cancel(id).status()).isEqualTo(BackgroundAgentManager.TaskStatus.CANCELLED);
    assertThat(manager.waitFor(id, 5_000).status())
        .isEqualTo(BackgroundAgentManager.TaskStatus.CANCELLED);
    manager.close();
  }

  private BackgroundAgentManager manager(BackgroundAgentManager.TaskRunner runner) {
    return new BackgroundAgentManager(
        runner,
        new ToolResultStore(temporary.resolve("results")),
        temporary.resolve("background.json"),
        Clock.systemUTC());
  }

  private ToolContext context(List<AgentEvent> events) {
    return new ToolContext(
        SessionId.of("session-1"),
        TurnId.of(1),
        temporary,
        events::add,
        Map.of("cancellationToken", new CancellationToken()));
  }
}
