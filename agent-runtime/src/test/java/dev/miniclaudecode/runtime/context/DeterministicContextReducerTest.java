package dev.miniclaudecode.runtime.context;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DeterministicContextReducerTest {
  @Test
  void preservesStructuredGoalChangesVerificationFailuresAndTaskState() {
    List<AgentMessage> messages = new ArrayList<>();
    messages.add(new SystemMessage("system"));
    messages.add(new UserMessage("Implement reliable save"));
    messages.add(
        new ToolMessage(
            "edit", "workspace:edit", "Applied approved change to src/Save.java", false));
    messages.add(new ToolMessage("failed", "shell:run", "Tests failed: expected 2", true));
    messages.add(new ToolMessage("passed", "shell:run", "Tests run: 2, Failures: 0", false));
    messages.add(
        new ToolMessage("tasks", "task:todo", "[x] inspect\n[>] verify\n[ ] summarize", false));

    for (int index = 0; index < 8; index++) {
      messages.add(new UserMessage("recent " + index));
    }

    List<AgentMessage> reduced = new DeterministicContextReducer(4, 64).reduce(messages);
    String summary =
        reduced.stream()
            .filter(SystemMessage.class::isInstance)
            .<String>map(AgentMessage::text)
            .filter(text -> text.startsWith("Conversation compact summary"))
            .findFirst()
            .orElseThrow();
    Assertions.assertThat(summary)
        .contains(
            new CharSequence[] {
              "Objective:",
              "Implement reliable save",
              "Changed files:",
              "src/Save.java",
              "Verification:",
              "Tests run: 2, Failures: 0",
              "Failed attempts:",
              "expected 2",
              "Remaining task state:",
              "summarize"
            });
  }
}
