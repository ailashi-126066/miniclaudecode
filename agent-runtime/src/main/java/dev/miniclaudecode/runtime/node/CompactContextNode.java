package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.context.ContextPipeline;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class CompactContextNode implements AsyncNodeAction<MiniClaudeState> {
  private final ContextPipeline pipeline;

  public CompactContextNode(ContextPipeline pipeline) {
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline must not be null");
  }

  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    Map<String, Object> update = new LinkedHashMap<>();
    update.put("messages", this.pipeline.transform(state.request(), state.messages()));
    update.put("error", "");
    update.put("failureType", "");
    update.put("failureRetryable", false);
    update.put("status", AgentStatus.RUNNING);
    update.put("compactionCount", state.compactionCount() + 1);
    update.put("trace", StateSchema.traceEntry("compact_context"));
    return CompletableFuture.completedFuture(Map.copyOf(update));
  }
}
