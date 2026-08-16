package dev.miniclaudecode.runtime.node.workflow;

import dev.miniclaudecode.runtime.AsyncNodeAction;
import dev.miniclaudecode.runtime.route.ResponseRouter;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.workflow.ExecutionMode;
import dev.miniclaudecode.runtime.workflow.ExecutionPhase;
import dev.miniclaudecode.runtime.workflow.TaskComplexityRouter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RouteExecutionNode implements AsyncNodeAction<MiniClaudeState> {
  private final ResponseRouter responseRouter;
  private final TaskComplexityRouter complexity = new TaskComplexityRouter();
  private final Map<String, AsyncNodeAction<MiniClaudeState>> recoveryActions;

  public RouteExecutionNode(
      ResponseRouter responseRouter,
      Map<String, AsyncNodeAction<MiniClaudeState>> recoveryActions) {
    this.responseRouter = responseRouter;
    this.recoveryActions = Map.copyOf(recoveryActions);
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    if ("tools".equals(state.workflowSource())) {
      return responseRouter
          .afterTools()
          .apply(state)
          .thenCompose(route -> translateTools(state, route));
    }
    return responseRouter.afterModel().apply(state).thenCompose(route -> translate(state, route));
  }

  private CompletableFuture<Map<String, Object>> translateTools(
      MiniClaudeState state, String route) {
    if ("compact".equals(route)) {
      return recoveryActions
          .get("compact")
          .apply(state)
          .thenApply(update -> WorkflowNodeSupport.route(update, "call"));
    }
    if ("create_plan".equals(route)) {
      return CompletableFuture.completedFuture(
          Map.of(
              MiniClaudeState.EXECUTION_MODE,
              ExecutionMode.PLANNED,
              MiniClaudeState.EXECUTION_PHASE,
              ExecutionPhase.PLAN_STEP,
              MiniClaudeState.WORKFLOW_ROUTE,
              "plan"));
    }
    return CompletableFuture.completedFuture(
        Map.of(MiniClaudeState.WORKFLOW_ROUTE, "model".equals(route) ? "call" : route));
  }

  private CompletableFuture<Map<String, Object>> translate(MiniClaudeState state, String route) {
    AsyncNodeAction<MiniClaudeState> recovery = recoveryActions.get(route);
    if (recovery != null) {
      String next = "invalid".equals(route) ? "finish" : "call";
      return recovery.apply(state).thenApply(update -> WorkflowNodeSupport.route(update, next));
    }
    if ("verify_step".equals(route)) {
      return CompletableFuture.completedFuture(Map.of(MiniClaudeState.WORKFLOW_ROUTE, "verify"));
    }
    if (!"finish".equals(route)) {
      return CompletableFuture.completedFuture(Map.of(MiniClaudeState.WORKFLOW_ROUTE, route));
    }
    if (state.executionPhase() != ExecutionPhase.DISCOVERY) {
      return CompletableFuture.completedFuture(Map.of(MiniClaudeState.WORKFLOW_ROUTE, "verify"));
    }
    TaskComplexityRouter.Decision decision = complexity.decide(state);
    Map<String, Object> update = new LinkedHashMap<>();
    update.put(MiniClaudeState.EXECUTION_MODE, decision.mode());
    if (decision.mode() == ExecutionMode.PLANNED) {
      update.put(MiniClaudeState.EXECUTION_PHASE, ExecutionPhase.PLAN_STEP);
      update.put(MiniClaudeState.WORKFLOW_ROUTE, "plan");
    } else if (decision.needsExecution()) {
      update.put(MiniClaudeState.EXECUTION_PHASE, ExecutionPhase.DIRECT);
      update.put(MiniClaudeState.DIRECT_ATTEMPTS, 1);
      update.put(MiniClaudeState.FINAL_TEXT, "");
      update.put(MiniClaudeState.WORKFLOW_ROUTE, "call");
    } else {
      update.put(MiniClaudeState.WORKFLOW_ROUTE, "verify");
    }
    return CompletableFuture.completedFuture(Map.copyOf(update));
  }
}
