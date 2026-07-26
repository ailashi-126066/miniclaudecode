package dev.miniclaudecode.runtime.context;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.runtime.context.ContextPlanner.Plan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ContextPlannerTest {
  @Test
  void reservesOutputTokensAndCompactsBeforeTheInputBudgetIsExhausted() {
    ContextPlanner planner = new ContextPlanner(0.8);
    ModelRequest request =
        new ModelRequest(
            "test", "fake", List.of(), List.of(), false, 20, Map.of("contextWindowTokens", 100));
    Plan plan = planner.plan(request, List.of(new UserMessage("x".repeat(300))));
    Assertions.assertThat(plan.inputBudgetTokens()).isEqualTo(80);
    Assertions.assertThat(plan.compact()).isTrue();
  }

  @Test
  void reducerSummarizesOldConstraintsAndFailuresButKeepsTheRecentToolPair() {
    List<AgentMessage> messages = new ArrayList<>();
    messages.add(new UserMessage("Never change public APIs"));
    messages.add(new ToolMessage("old", "shell:run", "compile failed", true));

    for (int index = 0; index < 8; index++) {
      messages.add(new UserMessage("older message " + index));
    }

    ToolCall recentCall = new ToolCall("recent", "workspace:read", "{}");
    AssistantMessage assistant =
        new AssistantMessage("", Optional.empty(), List.of(recentCall), Map.of());
    ToolMessage tool = new ToolMessage("recent", "workspace:read", "latest result", false);
    messages.add(assistant);
    messages.add(tool);
    List<AgentMessage> reduced = new DeterministicContextReducer(4, 64).reduce(messages);
    Assertions.assertThat(reduced)
        .anyMatch(
            message -> {
              if (message instanceof SystemMessage system
                  && system.text().contains("Never change public APIs")
                  && system.text().contains("compile failed")) {
                return true;
              }

              return false;
            });
    Assertions.assertThat(reduced).contains(new AgentMessage[] {assistant, tool});
  }
}
