package dev.miniclaudecode.runtime;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.runtime.context.ContextPlanner;
import dev.miniclaudecode.runtime.context.DeterministicContextReducer;
import dev.miniclaudecode.runtime.node.AwaitApprovalNode;
import dev.miniclaudecode.runtime.node.CallModelNode;
import dev.miniclaudecode.runtime.node.CompactContextNode;
import dev.miniclaudecode.runtime.node.ExecuteToolsNode;
import dev.miniclaudecode.runtime.node.FinishNode;
import dev.miniclaudecode.runtime.node.PrepareContextNode;
import dev.miniclaudecode.runtime.node.RecoverErrorNode;
import dev.miniclaudecode.runtime.node.RequireVerificationNode;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.route.ResponseRouter;
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

  public static final String PREPARE_CONTEXT = "prepare_context";
  public static final String CALL_MODEL = "call_model";
  public static final String EXECUTE_TOOLS = "execute_tools";
  public static final String AWAIT_APPROVAL = "await_approval";
  public static final String COMPACT_CONTEXT = "compact_context";
  public static final String RECOVER_ERROR = "recover_error";
  public static final String FINISH = "finish";
  public static final String REQUIRE_VERIFICATION = "require_verification";

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
    Objects.requireNonNull(modelClient, "modelClient must not be null");
    Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
    Objects.requireNonNull(limits, "limits must not be null");
    this.graph = compile(modelClient, toolExecutor, limits, checkpointSaver, cancellationToken);
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
      CancellationToken cancellationToken) {
    ContextPlanner contextPlanner = new ContextPlanner();
    RetryPolicy retryPolicy = new RetryPolicy();
    ResponseRouter router = new ResponseRouter(contextPlanner, retryPolicy);
    try {
      StateGraph<MiniClaudeState> stateGraph =
          new StateGraph<>(StateSchema.channels(), MiniClaudeState::new);
      stateGraph
          .addNode(PREPARE_CONTEXT, new PrepareContextNode())
          .addNode(CALL_MODEL, new CallModelNode(modelClient, limits, cancellationToken))
          .addNode(COMPACT_CONTEXT, new CompactContextNode(new DeterministicContextReducer()))
          .addNode(RECOVER_ERROR, new RecoverErrorNode(retryPolicy))
          .addNode(EXECUTE_TOOLS, new ExecuteToolsNode(toolExecutor, limits))
          .addNode(AWAIT_APPROVAL, new AwaitApprovalNode())
          .addNode(FINISH, new FinishNode())
          .addNode(REQUIRE_VERIFICATION, new RequireVerificationNode())
          .addEdge(START, PREPARE_CONTEXT)
          .addConditionalEdges(
              PREPARE_CONTEXT,
              router.afterPrepare(),
              Map.of("model", CALL_MODEL, "compact", COMPACT_CONTEXT))
          .addEdge(COMPACT_CONTEXT, CALL_MODEL)
          .addEdge(RECOVER_ERROR, CALL_MODEL)
          .addConditionalEdges(
              CALL_MODEL,
              router.afterModel(),
              Map.of(
                  "tools",
                  EXECUTE_TOOLS,
                  "compact",
                  COMPACT_CONTEXT,
                  "retry",
                  RECOVER_ERROR,
                  "verify",
                  REQUIRE_VERIFICATION,
                  "finish",
                  FINISH))
          .addEdge(REQUIRE_VERIFICATION, CALL_MODEL)
          .addConditionalEdges(
              EXECUTE_TOOLS,
              router.afterTools(),
              Map.of("model", CALL_MODEL, "approval", AWAIT_APPROVAL, "finish", FINISH))
          .addEdge(AWAIT_APPROVAL, EXECUTE_TOOLS)
          .addEdge(FINISH, END);
      // Budget node executions, not logical steps: a retried model call costs two executions
      // (recover_error + call_model), and compaction and the verification gate add their own. The
      // previous single-count budget tripped langgraph4j's recursion guard — an exception rather
      // than a clean FAILED state — before the turn's own step limits were ever reached.
      CompileConfig.Builder compileConfig =
          CompileConfig.builder()
              .recursionLimit(2 * (limits.maxModelSteps() + 1) + limits.maxToolSteps() + 16);
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
