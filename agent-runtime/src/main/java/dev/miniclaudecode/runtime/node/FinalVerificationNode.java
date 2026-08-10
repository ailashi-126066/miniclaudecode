package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class FinalVerificationNode implements AsyncNodeAction<MiniClaudeState> {
  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    return CompletableFuture.completedFuture(
        Map.of(
            MiniClaudeState.PLANNING_PHASE,
            "FINISH",
            MiniClaudeState.TRACE,
            StateSchema.traceEntry("final_verification")));
  }
}
