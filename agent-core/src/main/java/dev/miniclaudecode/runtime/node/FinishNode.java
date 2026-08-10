package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.output.OutputProtocol;
import dev.miniclaudecode.runtime.output.OutputProtocolRegistry;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class FinishNode implements AsyncNodeAction<MiniClaudeState> {
  private final OutputProtocolRegistry outputProtocols;

  public FinishNode() {
    this(new OutputProtocolRegistry());
  }

  public FinishNode(OutputProtocolRegistry outputProtocols) {
    this.outputProtocols =
        Objects.requireNonNull(outputProtocols, "outputProtocols must not be null");
  }

  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    // A cancelled turn also carries an error message so the reason reaches the transcript, but it
    // is not a failure: recomputing the status purely from `error` used to turn every Ctrl-C into a
    // red FAILED banner and an ERROR audit event, leaving the CANCELLED branch downstream dead.
    Map<String, Object> update = new LinkedHashMap<>();
    AgentStatus status = status(state);
    if (status == AgentStatus.COMPLETED) {
      OutputProtocol.Evaluation output =
          this.outputProtocols.evaluate(state.request(), state.finalText());
      if (output.valid()) {
        update.put("finalText", output.finalText());
      } else {
        status = AgentStatus.FAILED;
        update.put(
            "error",
            "model output did not satisfy the configured terminal protocol after "
                + state.outputRepairCount()
                + " repair attempt(s)");
      }
    }
    update.put("status", status);
    update.put("trace", StateSchema.traceEntry("finish"));
    return CompletableFuture.completedFuture(Map.copyOf(update));
  }

  private static AgentStatus status(MiniClaudeState state) {
    if (state.status() == AgentStatus.CANCELLED) {
      return AgentStatus.CANCELLED;
    }
    return state.error().isPresent() ? AgentStatus.FAILED : AgentStatus.COMPLETED;
  }
}
