package dev.miniclaudecode.runtime.node.workflow;

import dev.miniclaudecode.runtime.node.CreatePlanNode;
import dev.miniclaudecode.runtime.node.ExecutePlanStepNode;
import dev.miniclaudecode.runtime.node.ReplanNode;
import dev.miniclaudecode.runtime.node.SelectPlanStepNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.workflow.ExecutionPhase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class PlanControlNode implements AsyncNodeAction<MiniClaudeState> {
  private final CreatePlanNode create;
  private final SelectPlanStepNode select;
  private final ExecutePlanStepNode execute;
  private final ReplanNode replan;

  public PlanControlNode(
      CreatePlanNode create,
      SelectPlanStepNode select,
      ExecutePlanStepNode execute,
      ReplanNode replan) {
    this.create = create;
    this.select = select;
    this.execute = execute;
    this.replan = replan;
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    if (state.plan().isEmpty()) {
      return create.apply(state).thenCompose(update -> selectAndPrepare(state, update));
    }
    if ("REPLAN".equals(state.stepDecision())) {
      return replan.apply(state).thenCompose(update -> selectAndPrepare(state, update));
    }
    return selectAndPrepare(state, Map.of());
  }

  private CompletableFuture<Map<String, Object>> selectAndPrepare(
      MiniClaudeState original, Map<String, Object> first) {
    MiniClaudeState base = WorkflowNodeSupport.merge(original, first);
    return select
        .apply(base)
        .thenCompose(
            selected -> {
              Map<String, Object> accumulated = new LinkedHashMap<>();
              mergeUpdates(accumulated, first);
              mergeUpdates(accumulated, selected);
              MiniClaudeState next = WorkflowNodeSupport.merge(base, selected);
              if ("FINAL_VERIFICATION".equals(next.planningPhase())) {
                accumulated.put(MiniClaudeState.WORKFLOW_ROUTE, "verify");
                return CompletableFuture.completedFuture(Map.copyOf(accumulated));
              }
              if (!"EXECUTE_STEP".equals(next.planningPhase())) {
                accumulated.put(MiniClaudeState.WORKFLOW_ROUTE, "finish");
                return CompletableFuture.completedFuture(Map.copyOf(accumulated));
              }
              return execute
                  .apply(next)
                  .thenApply(
                      prepared -> {
                        mergeUpdates(accumulated, prepared);
                        accumulated.put(MiniClaudeState.EXECUTION_PHASE, ExecutionPhase.PLAN_STEP);
                        accumulated.put(MiniClaudeState.WORKFLOW_ROUTE, "call");
                        return Map.copyOf(accumulated);
                      });
            });
  }

  private static void mergeUpdates(Map<String, Object> accumulated, Map<String, Object> update) {
    Object previousTrace = accumulated.get(MiniClaudeState.TRACE);
    Object nextTrace = update.get(MiniClaudeState.TRACE);
    accumulated.putAll(update);
    if (previousTrace instanceof List<?> previous && nextTrace instanceof List<?> next) {
      List<Object> combined = new java.util.ArrayList<>(previous);
      combined.addAll(next);
      accumulated.put(MiniClaudeState.TRACE, List.copyOf(combined));
    }
  }
}
