package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.planning.PlanStep;
import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ExecutePlanStepNode implements AsyncNodeAction<MiniClaudeState> {
  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    PlanStep step =
        state
            .plan()
            .flatMap(plan -> plan.currentStep())
            .orElseThrow(() -> new IllegalStateException("Plan has no in-progress step"));
    List<AgentMessage> messages = new ArrayList<>(state.messages());
    messages.add(
        new SystemMessage(
            "ACTIVE PLAN STEP "
                + step.id()
                + " (attempt "
                + step.attempts()
                + "): "
                + step.description()
                + "\nAcceptance criteria:\n- "
                + String.join("\n- ", step.acceptanceCriteria())
                + "\nAllowed effects: "
                + step.expectedEffects()
                + "\nWork only on this step. Use tools as needed, then return a concise result for verification."));
    return CompletableFuture.completedFuture(
        Map.of(
            MiniClaudeState.MESSAGES,
            List.copyOf(messages),
            MiniClaudeState.PLANNING_PHASE,
            "EXECUTE_STEP",
            MiniClaudeState.TRACE,
            StateSchema.traceEntry("execute_step")));
  }
}
