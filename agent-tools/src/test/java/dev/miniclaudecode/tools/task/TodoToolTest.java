package dev.miniclaudecode.tools.task;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.task.TodoTool.Status;
import dev.miniclaudecode.tools.task.TodoTool.TodoItem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TodoToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void replacesAndListsSessionScopedChecklist() throws Exception {
    TodoTool tool = new TodoTool();
    String items =
        "{\"action\":\"replace\",\"items\":[{\"id\":\"1\",\"content\":\"inspect\",\"verification\":\"read source\",\"status\":\"done\"},{\"id\":\"2\",\"content\":\"fix\",\"status\":\"in_progress\"}]}";
    ToolResult updated = this.execute(tool, "session-1", items);
    ToolResult listed = this.execute(tool, "session-1", "{\"action\":\"list\"}");
    ToolResult other = this.execute(tool, "session-2", "{\"action\":\"list\"}");
    Assertions.assertThat(updated.summary())
        .contains(new CharSequence[] {"[x] 1 - inspect", "[>] 2 - fix"});
    Assertions.assertThat(listed.summary()).isEqualTo(updated.summary());
    Assertions.assertThat(other.summary()).isEqualTo("No todo items.");
  }

  @Test
  void emitsDurableTaskSnapshotsAndCanRestoreThem() throws Exception {
    TodoTool tool = new TodoTool();
    List<AgentEvent> events = new ArrayList<>();
    SessionId sessionId = SessionId.of("session-1");
    String items =
        "{\"action\":\"replace\",\"items\":[{\"id\":\"1\",\"content\":\"inspect\",\"verification\":\"read source\",\"status\":\"done\"}]}";
    tool.execute(
            new ToolCall("call-1", "task:todo", items),
            new ToolContext(
                sessionId, TurnId.of(1L), this.temporaryDirectory, events::add, Map.of()))
        .toCompletableFuture()
        .get();
    TodoTool restarted = new TodoTool();
    restarted.restore(sessionId, tool.items(sessionId));
    Assertions.assertThat(events)
        .extracting(AgentEvent::type)
        .containsExactly(AgentEventType.TASK_UPDATED);
    Assertions.assertThat(restarted.items(sessionId))
        .containsExactly(new TodoItem[] {new TodoItem("1", "inspect", "read source", Status.DONE)});
  }

  @Test
  void rejectsDuplicateIdsAndMultipleInProgressItems() throws Exception {
    TodoTool tool = new TodoTool();
    String invalid =
        "{\"action\":\"replace\",\"items\":[{\"id\":\"1\",\"content\":\"one\",\"status\":\"in_progress\"},{\"id\":\"2\",\"content\":\"two\",\"status\":\"in_progress\"}]}";
    ToolResult result = this.execute(tool, "session-1", invalid);
    Assertions.assertThat(result.status())
        .isEqualTo(dev.miniclaudecode.domain.tool.ToolResult.Status.FAILED);
    Assertions.assertThat(result.summary()).contains(new CharSequence[] {"only one todo item"});
  }

  private ToolResult execute(TodoTool tool, String session, String arguments) throws Exception {
    return (ToolResult)
        tool.execute(
                new ToolCall("call-1", "task:todo", arguments),
                new ToolContext(
                    new SessionId(session),
                    new TurnId(1L),
                    this.temporaryDirectory,
                    EventSink.NOOP,
                    Map.of()))
            .toCompletableFuture()
            .get();
  }
}
