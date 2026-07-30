package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.runtime.node.ExecuteToolsNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VerificationGateTest {

  @Test
  void routesBackToModelWhenAChangeHasNotBeenVerified() {
    MiniClaudeState state = state(messages(changed()), 0);

    assertThat(NormalTurnLoop.requiresVerification(state)).isTrue();
  }

  @Test
  void remainsRequiredAfterTheMaximumPromptCountUntilACommandActuallySucceeds() {
    MiniClaudeState state = state(messages(changed()), 2);

    assertThat(NormalTurnLoop.requiresVerification(state)).isTrue();
  }

  @Test
  void permitsCompletionAfterASuccessfulVerificationCommand() {
    List<AgentMessage> messages = messages(changed());
    messages.add(
        new AgentMessage.ToolMessage(
            "test-1",
            "shell:run",
            ExecuteToolsNode.VERIFICATION_SUCCEEDED_PREFIX + "Tests pass",
            false));

    assertThat(NormalTurnLoop.requiresVerification(state(messages, 0))).isFalse();
  }

  @Test
  void leavesLibraryCallersOptedOutByDefault() {
    MiniClaudeState state = state(messages(changed()), 0, false);

    assertThat(NormalTurnLoop.requiresVerification(state)).isFalse();
  }

  @Test
  void doesNotAcceptAnArbitrarySuccessfulShellCommandAsVerification() {
    List<AgentMessage> messages = messages(changed());
    messages.add(new AgentMessage.ToolMessage("shell-1", "shell:run", "hello", false));

    assertThat(NormalTurnLoop.requiresVerification(state(messages, 0))).isTrue();
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

    assertThat(NormalTurnLoop.hasIncompleteTasks(state)).isTrue();
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
