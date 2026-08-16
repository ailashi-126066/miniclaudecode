package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RequireVerificationNode implements AsyncNodeAction<MiniClaudeState> {
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    List<AgentMessage> messages = new ArrayList<>(state.messages());
    messages.add(
        new SystemMessage(
            "Completion gate: the turn still has unverified file changes or incomplete task items."
                + " Finish the task checklist and run the narrowest relevant test, build, lint, or"
                + " compile command with shell:run. If verification cannot run, inspect the failure"
                + " and report the concrete blocker; do not claim the task is verified."));
    return CompletableFuture.completedFuture(
        Map.of(
            "messages",
            List.copyOf(messages),
            "verificationPrompts",
            state.verificationPrompts() + 1,
            "trace",
            StateSchema.traceEntry("require_verification")));
  }
}
