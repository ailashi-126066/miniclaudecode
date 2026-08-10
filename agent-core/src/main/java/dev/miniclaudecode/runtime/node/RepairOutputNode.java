package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.output.OutputProtocol;
import dev.miniclaudecode.runtime.output.OutputProtocolRegistry;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/** Feeds a bounded format correction back to the model instead of guessing terminal output. */
public final class RepairOutputNode implements AsyncNodeAction<MiniClaudeState> {
  private final OutputProtocolRegistry protocols;

  public RepairOutputNode(OutputProtocolRegistry protocols) {
    this.protocols = Objects.requireNonNull(protocols, "protocols must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    OutputProtocol.Evaluation evaluation =
        this.protocols.evaluate(state.request(), state.finalText());
    return this.apply(state, evaluation.repairInstruction());
  }

  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state, String instruction) {
    List<AgentMessage> messages = new ArrayList<>(state.messages());
    messages.add(
        new UserMessage(Objects.requireNonNull(instruction, "instruction must not be null")));
    return CompletableFuture.completedFuture(
        Map.of(
            "messages",
            List.copyOf(messages),
            "outputRepairCount",
            state.outputRepairCount() + 1,
            "status",
            AgentStatus.RUNNING,
            "trace",
            StateSchema.traceEntry("repair_output")));
  }
}
