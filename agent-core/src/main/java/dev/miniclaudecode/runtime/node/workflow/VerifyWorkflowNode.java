package dev.miniclaudecode.runtime.node.workflow;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.planning.PlanStatus;
import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.node.VerifyPlanStepNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.verification.VerificationOutcome;
import dev.miniclaudecode.runtime.verification.VerificationPipeline;
import dev.miniclaudecode.runtime.verification.VerificationResult;
import dev.miniclaudecode.runtime.verification.VerificationScope;
import dev.miniclaudecode.runtime.workflow.ExecutionPhase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class VerifyWorkflowNode implements AsyncNodeAction<MiniClaudeState> {
  private static final int MAX_DIRECT_ATTEMPTS = 2;
  private final VerifyPlanStepNode planStep;
  private final VerificationPipeline pipeline;

  public VerifyWorkflowNode(VerifyPlanStepNode planStep, VerificationPipeline pipeline) {
    this.planStep = planStep;
    this.pipeline = pipeline;
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    if (state.executionPhase() == ExecutionPhase.PLAN_STEP
        && state.plan().map(plan -> plan.status() != PlanStatus.COMPLETED).orElse(false)) {
      return planStep
          .apply(state)
          .thenApply(
              update -> {
                MiniClaudeState verified = WorkflowNodeSupport.merge(state, update);
                String route = "BLOCKED".equals(verified.stepDecision()) ? "finish" : "plan";
                return WorkflowNodeSupport.route(update, route);
              });
    }
    VerificationScope scope =
        state.plan().map(plan -> plan.status() == PlanStatus.COMPLETED).orElse(false)
            ? VerificationScope.FINAL_TASK
            : VerificationScope.DIRECT_TASK;
    VerificationResult result = pipeline.verify(state, scope);
    if (result.outcome() == VerificationOutcome.PASS) {
      return CompletableFuture.completedFuture(
          scope == VerificationScope.FINAL_TASK
              ? Map.of(
                  MiniClaudeState.WORKFLOW_ROUTE,
                  "finish",
                  MiniClaudeState.TRACE,
                  dev.miniclaudecode.runtime.state.StateSchema.traceEntry("final_verification"))
              : Map.of(MiniClaudeState.WORKFLOW_ROUTE, "finish"));
    }
    if (result.outcome() == VerificationOutcome.RETRY
        && state.executionPhase() == ExecutionPhase.DIRECT
        && state.directAttempts() < maximumDirectAttempts(state)) {
      List<AgentMessage> messages = new ArrayList<>(state.messages());
      messages.add(
          new SystemMessage(
              "Verification failed: "
                  + result.reason()
                  + ". Correct the task using observed tool evidence, then verify once more."));
      Map<String, Object> update = new LinkedHashMap<>();
      update.put(MiniClaudeState.MESSAGES, List.copyOf(messages));
      update.put(MiniClaudeState.DIRECT_ATTEMPTS, state.directAttempts() + 1);
      update.put(MiniClaudeState.WORKFLOW_ROUTE, "call");
      return CompletableFuture.completedFuture(Map.copyOf(update));
    }
    return CompletableFuture.completedFuture(
        Map.of(
            MiniClaudeState.STATUS,
            dev.miniclaudecode.domain.session.AgentStatus.FAILED,
            MiniClaudeState.ERROR,
            result.reason().isBlank() ? "verification failed" : result.reason(),
            MiniClaudeState.WORKFLOW_ROUTE,
            "finish"));
  }

  private static int maximumDirectAttempts(MiniClaudeState state) {
    Object configured = state.request().attributes().get("maxDirectAttempts");
    int value = configured instanceof Number number ? number.intValue() : MAX_DIRECT_ATTEMPTS;
    return Math.max(1, Math.min(MAX_DIRECT_ATTEMPTS, value));
  }
}
