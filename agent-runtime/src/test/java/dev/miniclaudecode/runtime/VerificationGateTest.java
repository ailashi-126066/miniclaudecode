package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.context.ContextPlanner;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.runtime.node.RequireVerificationNode;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.route.ResponseRouter;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerificationGateTest {

  private final ResponseRouter router = new ResponseRouter(new ContextPlanner(), new RetryPolicy());

  @Test
  void routesBackToModelWhenAChangeHasNotBeenVerified() {
    MiniClaudeState state = state(messages(changed()), 0);

    assertThat(router.afterModel().apply(state).join()).isEqualTo("verify");

    Map<String, Object> update = new RequireVerificationNode().apply(state).join();
    assertThat((List<?>) update.get(MiniClaudeState.MESSAGES))
        .last()
        .asString()
        .contains("Completion gate");
  }

  @Test
  void permitsCompletionAfterASuccessfulVerificationCommand() {
    List<AgentMessage> messages = messages(changed());
    messages.add(new AgentMessage.ToolMessage("test-1", "shell:run", "Tests pass", false));

    assertThat(router.afterModel().apply(state(messages, 0)).join()).isEqualTo("finish");
  }

  @Test
  void leavesLibraryCallersOptedOutByDefault() {
    MiniClaudeState state = state(messages(changed()), 0, false);

    assertThat(router.afterModel().apply(state).join()).isEqualTo("finish");
  }

  @Test
  void routesBackToModelWhileTheLatestTaskChecklistIsIncomplete() {
    List<AgentMessage> messages = new ArrayList<>();
    messages.add(new AgentMessage.UserMessage("Fix App"));
    messages.add(
        new AgentMessage.ToolMessage(
            "tasks-1", "task:todo", "[x] inspect\n[>] verify\n[ ] summarize", false));
    ModelRequest request =
        new ModelRequest(
            "test", "fake", messages, List.of(), false, 100, Map.of("requireTaskCompletion", true));
    MiniClaudeState state =
        new MiniClaudeState(
            Map.of(MiniClaudeState.REQUEST, request, MiniClaudeState.MESSAGES, messages));

    assertThat(router.afterModel().apply(state).join()).isEqualTo("verify");
  }

  private static AgentMessage.ToolMessage changed() {
    return new AgentMessage.ToolMessage(
        "edit-1", "workspace:edit", "Applied approved change to src/App.java", false);
  }

  private static List<AgentMessage> messages(AgentMessage.ToolMessage changed) {
    List<AgentMessage> messages = new ArrayList<>();
    messages.add(new AgentMessage.UserMessage("Fix App"));
    messages.add(changed);
    messages.add(new AgentMessage.AssistantMessage("Done", java.util.Optional.empty(), Map.of()));
    return messages;
  }

  private static MiniClaudeState state(List<AgentMessage> messages, int prompts) {
    return state(messages, prompts, true);
  }

  private static MiniClaudeState state(
      List<AgentMessage> messages, int prompts, boolean requireVerification) {
    ModelRequest request =
        new ModelRequest(
            "test",
            "fake",
            messages,
            List.of(),
            false,
            100,
            requireVerification ? Map.of("requireVerification", true) : Map.of());
    return new MiniClaudeState(
        Map.of(
            MiniClaudeState.REQUEST,
            request,
            MiniClaudeState.MESSAGES,
            messages,
            MiniClaudeState.FINAL_TEXT,
            "Done",
            MiniClaudeState.VERIFICATION_PROMPTS,
            prompts));
  }
}
