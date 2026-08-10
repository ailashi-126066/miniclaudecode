package dev.miniclaudecode.runtime.node.workflow;

import dev.miniclaudecode.runtime.node.PrepareContextNode;
import dev.miniclaudecode.runtime.route.ResponseRouter;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class PrepareWorkflowNode implements AsyncNodeAction<MiniClaudeState> {
  private final PrepareContextNode prepare = new PrepareContextNode();
  private final AsyncNodeAction<MiniClaudeState> compact;
  private final ResponseRouter router;

  public PrepareWorkflowNode(AsyncNodeAction<MiniClaudeState> compact, ResponseRouter router) {
    this.compact = compact;
    this.router = router;
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    return prepare
        .apply(state)
        .thenCompose(
            prepared -> {
              MiniClaudeState next = WorkflowNodeSupport.merge(state, prepared);
              return router
                  .afterPrepare()
                  .apply(next)
                  .thenCompose(
                      route ->
                          "compact".equals(route)
                              ? compact
                                  .apply(next)
                                  .thenApply(update -> WorkflowNodeSupport.route(update, "call"))
                              : CompletableFuture.completedFuture(
                                  WorkflowNodeSupport.route(prepared, "call")));
            });
  }
}
