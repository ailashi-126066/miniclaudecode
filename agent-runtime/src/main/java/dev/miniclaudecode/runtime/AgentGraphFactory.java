package dev.miniclaudecode.runtime;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.runtime.node.AwaitApprovalNode;
import dev.miniclaudecode.runtime.node.CallModelNode;
import dev.miniclaudecode.runtime.node.CreatePlanNode;
import dev.miniclaudecode.runtime.node.ExecutePlanStepNode;
import dev.miniclaudecode.runtime.node.ExecuteToolsNode;
import dev.miniclaudecode.runtime.node.FailCompletionNode;
import dev.miniclaudecode.runtime.node.FinalVerificationNode;
import dev.miniclaudecode.runtime.node.FinishNode;
import dev.miniclaudecode.runtime.node.PrepareContextNode;
import dev.miniclaudecode.runtime.node.RecoverErrorNode;
import dev.miniclaudecode.runtime.node.RepairOutputNode;
import dev.miniclaudecode.runtime.node.ReplanNode;
import dev.miniclaudecode.runtime.node.RequireVerificationNode;
import dev.miniclaudecode.runtime.node.SelectPlanStepNode;
import dev.miniclaudecode.runtime.node.VerifyPlanStepNode;
import dev.miniclaudecode.runtime.output.EngineeringReportValidator;
import dev.miniclaudecode.runtime.output.OutputProtocolRegistry;
import dev.miniclaudecode.runtime.output.RagCitationValidator;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.route.ResponseRouter;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;

public final class AgentGraphFactory {

  public static final String PREPARE_CONTEXT = "prepare_context";
  public static final String CALL_MODEL = "call_model";
  public static final String EXECUTE_TOOLS = "execute_tools";
  public static final String AWAIT_APPROVAL = "await_approval";
  public static final String COMPACT_CONTEXT = "compact_context";
  public static final String RECOVER_ERROR = "recover_error";
  public static final String REQUIRE_VERIFICATION = "require_verification";
  public static final String REPAIR_OUTPUT = "repair_output";
  public static final String REPAIR_CITATIONS = "repair_citations";
  public static final String REPAIR_REPORT = "repair_report";
  public static final String FAIL_COMPLETION = "fail_completion";
  public static final String FINISH = "finish";
  public static final String CREATE_PLAN = "create_plan";
  public static final String SELECT_STEP = "select_step";
  public static final String EXECUTE_STEP = "execute_step";
  public static final String VERIFY_STEP = "verify_step";
  public static final String REPLAN = "replan";
  public static final String FINAL_VERIFICATION = "final_verification";

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
        TurnProgressListener.noOp(),
        PlanProgressListener.noOp());
  }

  public AgentGraphFactory(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      BaseCheckpointSaver checkpointSaver,
      CancellationToken cancellationToken,
      TurnProgressListener progressListener) {
    this(
        modelClient,
        toolExecutor,
        limits,
        checkpointSaver,
        cancellationToken,
        progressListener,
        PlanProgressListener.noOp());
  }

  public AgentGraphFactory(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      BaseCheckpointSaver checkpointSaver,
      CancellationToken cancellationToken,
      TurnProgressListener progressListener,
      PlanProgressListener planProgressListener) {
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
            Objects.requireNonNull(progressListener, "progressListener must not be null"),
            Objects.requireNonNull(planProgressListener, "planProgressListener must not be null"));
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
      TurnProgressListener progressListener,
      PlanProgressListener planProgressListener) {
    dev.miniclaudecode.context.ContextPlanner contextPlanner =
        new dev.miniclaudecode.context.ContextPlanner();
    RetryPolicy retryPolicy = new RetryPolicy();
    OutputProtocolRegistry outputProtocols = new OutputProtocolRegistry();
    RagCitationValidator citations = new RagCitationValidator();
    EngineeringReportValidator reports = new EngineeringReportValidator();
    ResponseRouter router =
        new ResponseRouter(contextPlanner, retryPolicy, outputProtocols, citations, reports);
    Clock planningClock = Clock.systemUTC();
    dev.miniclaudecode.planning.TaskPlanner taskPlanner =
        new dev.miniclaudecode.planning.StructuredTaskPlanner(modelClient, planningClock);
    RepairOutputNode repairOutput = new RepairOutputNode(outputProtocols);
    AsyncNodeAction<MiniClaudeState> repairCitations =
        state ->
            repairOutput.apply(
                state,
                citations
                    .evaluate(state.request(), state.messages(), state.finalText())
                    .repairInstruction());
    AsyncNodeAction<MiniClaudeState> repairReport =
        state ->
            repairOutput.apply(
                state, reports.evaluate(state.messages(), state.finalText()).repairInstruction());
    try {
      StateGraph<MiniClaudeState> stateGraph =
          new StateGraph<>(StateSchema.channels(), MiniClaudeState::new);
      stateGraph
          .addNode(PREPARE_CONTEXT, new PrepareContextNode())
          .addNode(
              CALL_MODEL,
              new ProgressReportingNode(
                  new CallModelNode(modelClient, limits, cancellationToken),
                  "before_model",
                  "after_model",
                  progressListener))
          .addNode(
              EXECUTE_TOOLS,
              new ProgressReportingNode(
                  new ExecuteToolsNode(toolExecutor, limits),
                  "before_tools",
                  "after_tools",
                  progressListener))
          .addNode(AWAIT_APPROVAL, new AwaitApprovalNode())
          .addNode(
              CREATE_PLAN, new CreatePlanNode(taskPlanner, planningClock, planProgressListener))
          .addNode(SELECT_STEP, new SelectPlanStepNode(planningClock, planProgressListener))
          .addNode(EXECUTE_STEP, new ExecutePlanStepNode())
          .addNode(VERIFY_STEP, new VerifyPlanStepNode(planningClock, planProgressListener))
          .addNode(REPLAN, new ReplanNode(taskPlanner, planningClock, planProgressListener))
          .addNode(FINAL_VERIFICATION, new FinalVerificationNode())
          .addNode(COMPACT_CONTEXT, new SemanticCompactContextNode(modelClient, progressListener))
          .addNode(RECOVER_ERROR, new RecoverErrorNode(retryPolicy))
          .addNode(REQUIRE_VERIFICATION, new RequireVerificationNode())
          .addNode(REPAIR_OUTPUT, repairOutput)
          .addNode(REPAIR_CITATIONS, repairCitations)
          .addNode(REPAIR_REPORT, repairReport)
          .addNode(FAIL_COMPLETION, new FailCompletionNode())
          .addNode(FINISH, new FinishNode(outputProtocols))
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
              Map.ofEntries(
                  Map.entry("tools", EXECUTE_TOOLS),
                  Map.entry("compact", COMPACT_CONTEXT),
                  Map.entry("retry", RECOVER_ERROR),
                  Map.entry("verify", REQUIRE_VERIFICATION),
                  Map.entry("verify_step", VERIFY_STEP),
                  Map.entry("repair_output", REPAIR_OUTPUT),
                  Map.entry("repair_citations", REPAIR_CITATIONS),
                  Map.entry("repair_report", REPAIR_REPORT),
                  Map.entry("invalid", FAIL_COMPLETION),
                  Map.entry("finish", FINISH)))
          .addEdge(REQUIRE_VERIFICATION, CALL_MODEL)
          .addEdge(REPAIR_OUTPUT, CALL_MODEL)
          .addEdge(REPAIR_CITATIONS, CALL_MODEL)
          .addEdge(REPAIR_REPORT, CALL_MODEL)
          .addConditionalEdges(
              EXECUTE_TOOLS,
              router.afterTools(),
              Map.of(
                  "model", CALL_MODEL,
                  "compact", COMPACT_CONTEXT,
                  "approval", AWAIT_APPROVAL,
                  "create_plan", CREATE_PLAN,
                  "finish", FINISH))
          .addEdge(CREATE_PLAN, SELECT_STEP)
          .addConditionalEdges(
              SELECT_STEP,
              router.afterSelectStep(),
              Map.of(
                  "execute", EXECUTE_STEP,
                  "final_verify", FINAL_VERIFICATION,
                  "finish", FINISH))
          .addEdge(EXECUTE_STEP, CALL_MODEL)
          .addConditionalEdges(
              VERIFY_STEP,
              router.afterVerifyStep(),
              Map.of("select", SELECT_STEP, "replan", REPLAN, "finish", FINISH))
          .addEdge(REPLAN, SELECT_STEP)
          .addConditionalEdges(
              FINAL_VERIFICATION,
              router.afterModel(),
              Map.ofEntries(
                  Map.entry("tools", EXECUTE_TOOLS),
                  Map.entry("compact", COMPACT_CONTEXT),
                  Map.entry("retry", RECOVER_ERROR),
                  Map.entry("verify", REQUIRE_VERIFICATION),
                  Map.entry("repair_output", REPAIR_OUTPUT),
                  Map.entry("repair_citations", REPAIR_CITATIONS),
                  Map.entry("repair_report", REPAIR_REPORT),
                  Map.entry("invalid", FAIL_COMPLETION),
                  Map.entry("finish", FINISH)))
          .addEdge(AWAIT_APPROVAL, EXECUTE_TOOLS)
          .addEdge(FAIL_COMPLETION, FINISH)
          .addEdge(FINISH, END);
      // Budget node executions, not logical steps: a retried model call costs two executions
      // (recover_error + call_model), and compaction and the verification gate add their own. The
      // previous single-count budget tripped langgraph4j's recursion guard — an exception rather
      // than a clean FAILED state — before the turn's own step limits were ever reached.
      CompileConfig.Builder compileConfig =
          CompileConfig.builder()
              .recursionLimit(4 * (limits.maxModelSteps() + 1) + limits.maxToolSteps() + 24);
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
