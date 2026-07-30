package dev.miniclaudecode.runtime;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.runtime.node.AwaitApprovalNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.Map;
import java.util.Objects;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

public final class AgentGraphFactory {

  public static final String NORMAL_LOOP = "normal_loop";
  public static final String AWAIT_APPROVAL = "await_approval";

  private final CompiledGraph<MiniClaudeState> graph;

  public AgentGraphFactory(ModelClient modelClient, ToolExecutor toolExecutor, TurnLimits limits) {
    this(modelClient, toolExecutor, limits, null);
  }

  public AgentGraphFactory(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      BaseCheckpointSaver checkpointSaver) {
    this(modelClient, toolExecutor, limits, checkpointSaver, null);
  }

  public AgentGraphFactory(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      BaseCheckpointSaver checkpointSaver,
      CancellationToken cancellationToken) {
    this(
        modelClient,
        toolExecutor,
        limits,
        checkpointSaver,
        cancellationToken,
        TurnProgressListener.noOp());
  }

  public AgentGraphFactory(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      BaseCheckpointSaver checkpointSaver,
      CancellationToken cancellationToken,
      TurnProgressListener progressListener) {
    Objects.requireNonNull(modelClient, "modelClient must not be null");
    Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    this.graph =
        compile(
            modelClient,
            toolExecutor,
            limits,
            checkpointSaver,
            cancellationToken,
            Objects.requireNonNull(progressListener, "progressListener must not be null"));
  }

  public MiniClaudeState run(ModelRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    return graph
        .invoke(StateSchema.initialInput(request))
        .orElseThrow(() -> new IllegalStateException("agent graph produced no final state"));
  }

  public MiniClaudeState start(SessionId sessionId, ModelRequest request) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(request, "request must not be null");
    return graph
        .invoke(StateSchema.initialInput(request), runnableConfig(sessionId))
        .orElseThrow(() -> new IllegalStateException("agent graph produced no state"));
  }

  public MiniClaudeState resume(SessionId sessionId, ApprovalDecision decision) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(decision, "decision must not be null");
    return graph
        .invoke(
            GraphInput.resume(Map.of(MiniClaudeState.APPROVAL_DECISION, decision)),
            runnableConfig(sessionId))
        .orElseThrow(() -> new IllegalStateException("agent graph produced no resumed state"));
  }

  private static CompiledGraph<MiniClaudeState> compile(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      BaseCheckpointSaver checkpointSaver,
      CancellationToken cancellationToken,
      TurnProgressListener progressListener) {
    try {
      StateGraph<MiniClaudeState> stateGraph =
          new StateGraph<>(StateSchema.channels(), MiniClaudeState::new);
      stateGraph
          .addNode(
              NORMAL_LOOP,
              new NormalTurnLoop(
                  modelClient, toolExecutor, limits, cancellationToken, progressListener))
          .addNode(AWAIT_APPROVAL, new AwaitApprovalNode())
          .addEdge(START, NORMAL_LOOP)
          .addConditionalEdges(
              NORMAL_LOOP,
              state ->
                  java.util.concurrent.CompletableFuture.completedFuture(
                      state.status()
                              == dev.miniclaudecode.domain.session.AgentStatus.WAITING_APPROVAL
                          ? "approval"
                          : "finish"),
              Map.of("approval", AWAIT_APPROVAL, "finish", END))
          .addEdge(AWAIT_APPROVAL, NORMAL_LOOP);
      // Budget node executions, not logical steps: a retried model call costs two executions
      // (recover_error + call_model), and compaction and the verification gate add their own. The
      // previous single-count budget tripped langgraph4j's recursion guard — an exception rather
      // than a clean FAILED state — before the turn's own step limits were ever reached.
      CompileConfig.Builder compileConfig = CompileConfig.builder().recursionLimit(6);
      if (checkpointSaver != null) {
        compileConfig.checkpointSaver(checkpointSaver).interruptAfter(AWAIT_APPROVAL);
      }
      return stateGraph.compile(compileConfig.build());
    } catch (GraphStateException error) {
      throw new IllegalStateException("cannot compile agent graph", error);
    }
  }

  private static RunnableConfig runnableConfig(SessionId sessionId) {
    return RunnableConfig.builder().threadId(sessionId.value()).build();
  }
}
