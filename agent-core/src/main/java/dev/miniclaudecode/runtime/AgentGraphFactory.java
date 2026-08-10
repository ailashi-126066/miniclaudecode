package dev.miniclaudecode.runtime;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.runtime.middleware.AgentMiddleware;
import dev.miniclaudecode.runtime.middleware.MiddlewareChain;
import dev.miniclaudecode.runtime.node.AwaitApprovalNode;
import dev.miniclaudecode.runtime.node.CallModelNode;
import dev.miniclaudecode.runtime.node.CreatePlanNode;
import dev.miniclaudecode.runtime.node.ExecutePlanStepNode;
import dev.miniclaudecode.runtime.node.ExecuteToolsNode;
import dev.miniclaudecode.runtime.node.FailCompletionNode;
import dev.miniclaudecode.runtime.node.FinishNode;
import dev.miniclaudecode.runtime.node.RecoverErrorNode;
import dev.miniclaudecode.runtime.node.RepairOutputNode;
import dev.miniclaudecode.runtime.node.ReplanNode;
import dev.miniclaudecode.runtime.node.RequireVerificationNode;
import dev.miniclaudecode.runtime.node.SelectPlanStepNode;
import dev.miniclaudecode.runtime.node.VerifyPlanStepNode;
import dev.miniclaudecode.runtime.node.workflow.PlanControlNode;
import dev.miniclaudecode.runtime.node.workflow.PrepareWorkflowNode;
import dev.miniclaudecode.runtime.node.workflow.RouteExecutionNode;
import dev.miniclaudecode.runtime.node.workflow.VerifyWorkflowNode;
import dev.miniclaudecode.runtime.output.EngineeringReportValidator;
import dev.miniclaudecode.runtime.output.OutputProtocolRegistry;
import dev.miniclaudecode.runtime.output.RagCitationValidator;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.route.ResponseRouter;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import dev.miniclaudecode.runtime.verification.StateEvidenceVerifier;
import dev.miniclaudecode.runtime.verification.VerificationPipeline;
import java.time.Clock;
import java.util.List;
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
  public static final String ROUTE_EXECUTION = "route_execution";
  public static final String PLAN_CONTROL = "plan_control";
  public static final String VERIFY = "verify";
  public static final String FINISH = "finish";

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
    this(
        modelClient,
        toolExecutor,
        limits,
        checkpointSaver,
        cancellationToken,
        progressListener,
        planProgressListener,
        List.of());
  }

  public AgentGraphFactory(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      BaseCheckpointSaver checkpointSaver,
      CancellationToken cancellationToken,
      TurnProgressListener progressListener,
      PlanProgressListener planProgressListener,
      List<AgentMiddleware> middleware) {
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
            Objects.requireNonNull(planProgressListener, "planProgressListener must not be null"),
            new MiddlewareChain(Objects.requireNonNull(middleware, "middleware must not be null")));
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
      PlanProgressListener planProgressListener,
      MiddlewareChain middleware) {
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
    AsyncNodeAction<MiniClaudeState> compact =
        new SemanticCompactContextNode(modelClient, progressListener);
    AsyncNodeAction<MiniClaudeState> callModel =
        state -> {
          middleware.beforeModel(state);
          return new ProgressReportingNode(
                  new CallModelNode(modelClient, limits, cancellationToken),
                  "before_model",
                  "after_model",
                  progressListener)
              .apply(state)
              .thenApply(
                  update -> {
                    middleware.afterModel(state, update);
                    Map<String, Object> marked = new java.util.LinkedHashMap<>(update);
                    marked.put(MiniClaudeState.WORKFLOW_SOURCE, "model");
                    return Map.copyOf(marked);
                  });
        };
    AsyncNodeAction<MiniClaudeState> executeTools =
        state -> {
          middleware.beforeTools(state);
          return new ProgressReportingNode(
                  new ExecuteToolsNode(toolExecutor, limits),
                  "before_tools",
                  "after_tools",
                  progressListener)
              .apply(state)
              .thenApply(
                  update -> {
                    middleware.afterTools(state, update);
                    Map<String, Object> marked = new java.util.LinkedHashMap<>(update);
                    marked.put(MiniClaudeState.WORKFLOW_SOURCE, "tools");
                    return Map.copyOf(marked);
                  });
        };
    AsyncNodeAction<MiniClaudeState> finish =
        state ->
            new FinishNode(outputProtocols)
                .apply(state)
                .thenApply(
                    update -> {
                      Map<String, Object> merged = new java.util.LinkedHashMap<>(state.data());
                      merged.putAll(update);
                      middleware.afterTurn(new MiniClaudeState(Map.copyOf(merged)));
                      return update;
                    });
    AsyncNodeAction<MiniClaudeState> prepare =
        state -> {
          middleware.beforeTurn(state);
          return new PrepareWorkflowNode(compact, router).apply(state);
        };
    AsyncNodeAction<MiniClaudeState> routeExecution =
        new RouteExecutionNode(
            router,
            Map.ofEntries(
                Map.entry("compact", compact),
                Map.entry("retry", new RecoverErrorNode(retryPolicy)),
                Map.entry("verify", new RequireVerificationNode()),
                Map.entry("repair_output", repairOutput),
                Map.entry("repair_citations", repairCitations),
                Map.entry("repair_report", repairReport),
                Map.entry("invalid", new FailCompletionNode())));
    AsyncNodeAction<MiniClaudeState> planControl =
        new PlanControlNode(
            new CreatePlanNode(taskPlanner, planningClock, planProgressListener),
            new SelectPlanStepNode(planningClock, planProgressListener),
            new ExecutePlanStepNode(),
            new ReplanNode(taskPlanner, planningClock, planProgressListener));
    AsyncNodeAction<MiniClaudeState> verify =
        new VerifyWorkflowNode(
            new VerifyPlanStepNode(planningClock, planProgressListener),
            new VerificationPipeline(java.util.List.of(new StateEvidenceVerifier())));
    try {
      StateGraph<MiniClaudeState> stateGraph =
          new StateGraph<>(StateSchema.channels(), MiniClaudeState::new);
      stateGraph
          .addNode(PREPARE_CONTEXT, prepare)
          .addNode(CALL_MODEL, callModel)
          .addNode(EXECUTE_TOOLS, executeTools)
          .addNode(ROUTE_EXECUTION, routeExecution)
          .addNode(PLAN_CONTROL, planControl)
          .addNode(AWAIT_APPROVAL, new AwaitApprovalNode())
          .addNode(VERIFY, verify)
          .addNode(FINISH, finish)
          .addEdge(START, PREPARE_CONTEXT)
          .addConditionalEdges(
              PREPARE_CONTEXT,
              state ->
                  java.util.concurrent.CompletableFuture.completedFuture(state.workflowRoute()),
              Map.of("call", CALL_MODEL))
          .addConditionalEdges(
              CALL_MODEL,
              state ->
                  java.util.concurrent.CompletableFuture.completedFuture(
                      state.pendingToolCalls().isEmpty() ? "route" : "tools"),
              Map.of("route", ROUTE_EXECUTION, "tools", EXECUTE_TOOLS))
          .addConditionalEdges(
              EXECUTE_TOOLS,
              state ->
                  java.util.concurrent.CompletableFuture.completedFuture(
                      state.pendingApproval().isPresent() ? "approval" : "route"),
              Map.of(
                  "call", CALL_MODEL,
                  "route", ROUTE_EXECUTION,
                  "approval", AWAIT_APPROVAL,
                  "finish", FINISH))
          .addConditionalEdges(
              ROUTE_EXECUTION,
              state ->
                  java.util.concurrent.CompletableFuture.completedFuture(state.workflowRoute()),
              Map.of(
                  "call", CALL_MODEL,
                  "plan", PLAN_CONTROL,
                  "verify", VERIFY,
                  "finish", FINISH))
          .addConditionalEdges(
              PLAN_CONTROL,
              state ->
                  java.util.concurrent.CompletableFuture.completedFuture(state.workflowRoute()),
              Map.of("call", CALL_MODEL, "verify", VERIFY, "finish", FINISH))
          .addConditionalEdges(
              VERIFY,
              state ->
                  java.util.concurrent.CompletableFuture.completedFuture(state.workflowRoute()),
              Map.of("call", CALL_MODEL, "plan", PLAN_CONTROL, "finish", FINISH))
          .addEdge(AWAIT_APPROVAL, EXECUTE_TOOLS)
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
