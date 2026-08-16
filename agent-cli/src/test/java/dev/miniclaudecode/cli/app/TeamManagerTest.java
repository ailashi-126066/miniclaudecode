package dev.miniclaudecode.cli.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.cli.app.BackgroundAgentManager.RunResult;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamManagerTest {
  @TempDir Path temporary;

  @Test
  void runsLeadAndThreeMembersWithMailboxAndPersistentState() {
    BackgroundAgentManager background = background();
    TeamManager teams =
        new TeamManager(background, temporary.resolve("teams.json"), Clock.systemUTC());
    ToolContext context = context();
    String teamId = teams.create("release", context);
    teams.join(teamId, "reader", "research", false, context);
    teams.join(teamId, "reviewer", "review", false, context);
    teams.join(teamId, "writer", "implementation", true, context);

    TeamManager.TeamTask first =
        teams.assign(teamId, "reader", "inspect code", "snapshot", context);
    TeamManager.TeamTask second =
        teams.assign(teamId, "reviewer", "review plan", "snapshot", context);
    TeamManager.TeamTask third =
        teams.assign(teamId, "writer", "implement change", "snapshot", context);
    background.waitFor(first.backgroundTaskId(), 5_000);
    background.waitFor(second.backgroundTaskId(), 5_000);
    background.waitFor(third.backgroundTaskId(), 5_000);
    teams.message(teamId, "lead", "writer", third.id(), "instruction", "report result", context);

    TeamManager.TeamSnapshot snapshot = teams.status(teamId);

    assertThat(snapshot.members()).hasSize(4);
    assertThat(snapshot.tasks()).hasSize(3).allMatch(task -> task.status().equals("COMPLETED"));
    assertThat(teams.inbox(teamId, "writer"))
        .singleElement()
        .satisfies(message -> assertThat(message.body()).isEqualTo("report result"));

    TeamManager restored =
        new TeamManager(background, temporary.resolve("teams.json"), Clock.systemUTC());
    assertThat(restored.status(teamId).tasks()).hasSize(3);
    ToolContext outsider =
        new ToolContext(
            SessionId.of("other-session"), TurnId.of(1), temporary, EventSink.NOOP, Map.of());
    assertThatThrownBy(() -> restored.join(teamId, "intruder", "writer", true, outsider))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("team lead");
    assertThat(restored.stop(teamId, context).status()).isEqualTo("STOPPED");
    assertThat(restored.archive(teamId, context).status()).isEqualTo("ARCHIVED");
    background.close();
  }

  private BackgroundAgentManager background() {
    return new BackgroundAgentManager(
        (spec, parent, cancellation) ->
            new RunResult(AgentStatus.COMPLETED, "done: " + spec.task(), 1, 1),
        new ToolResultStore(temporary.resolve("results")),
        temporary.resolve("background.json"),
        Clock.systemUTC());
  }

  private ToolContext context() {
    return new ToolContext(
        SessionId.of("lead-session"), TurnId.of(1), temporary, EventSink.NOOP, Map.of());
  }
}
