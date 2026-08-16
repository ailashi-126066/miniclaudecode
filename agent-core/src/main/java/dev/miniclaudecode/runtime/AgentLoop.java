package dev.miniclaudecode.runtime;

import dev.miniclaudecode.context.ContextPlanner;
import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.planning.StructuredTaskPlanner;
import dev.miniclaudecode.planning.TaskPlanner;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * Explicit model/tool loop used by both interactive and delegated agents.
 *
 * <p>The node classes remain temporarily reusable migration units, but control flow lives here
 * instead of in a graph definition. This makes the model/tool iteration, approval pause and
 * terminal conditions visible in one place without moving UI or component wiring into the loop.
 */
public final class AgentLoop {
  private static final String CALL = "call";
  private static final String TOOLS = "tools";
  private static final String ROUTE = "route";
  private static final String PLAN = "plan";
  private static final String VERIFY = "verify";
  private static final String FINISH = "finish";

  private final TurnLimits limits;
  private final MiddlewareChain middleware;
  private final AsyncNodeAction<MiniClaudeState> prepare;
  private final AsyncNodeAction<MiniClaudeState> callModel;
  private final AsyncNodeAction<MiniClaudeState> executeTools;
  private final AsyncNodeAction<MiniClaudeState> awaitApproval;
  private final AsyncNodeAction<MiniClaudeState> routeExecution;
  private final AsyncNodeAction<MiniClaudeState> planControl;
  private final AsyncNodeAction<MiniClaudeState> verify;
  private final AsyncNodeAction<MiniClaudeState> finish;

  public AgentLoop(ModelClient modelClient, ToolExecutor toolExecutor, TurnLimits limits) {
    this(
        modelClient,
        toolExecutor,
        limits,
        null,
        TurnProgressListener.noOp(),
        PlanProgressListener.noOp(),
        List.of());
  }

  public AgentLoop(
      ModelClient modelClient,
      ToolExecutor toolExecutor,
      TurnLimits limits,
      CancellationToken cancellationToken,
      TurnProgressListener progressListener,
      PlanProgressListener planProgressListener,
      List<AgentMiddleware> middleware) {
    Objects.requireNonNull(modelClient, "modelClient must not be null");
    Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(progressListener, "progressListener must not be null");
    Objects.requireNonNull(planProgressListener, "planProgressListener must not be null");
    this.middleware =
        new MiddlewareChain(Objects.requireNonNull(middleware, "middleware must not be null"));

    ContextPlanner contextPlanner = new ContextPlanner();
    RetryPolicy retryPolicy = new RetryPolicy();
    OutputProtocolRegistry outputProtocols = new OutputProtocolRegistry();
    RagCitationValidator citations = new RagCitationValidator();
    EngineeringReportValidator reports = new EngineeringReportValidator();
    ResponseRouter router =
        new ResponseRouter(contextPlanner, retryPolicy, outputProtocols, citations, reports);
    Clock planningClock = Clock.systemUTC();
    TaskPlanner taskPlanner = new StructuredTaskPlanner(modelClient, planningClock);
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
    CallModelNode modelNode = new CallModelNode(modelClient, limits, cancellationToken);
    ExecuteToolsNode toolsNode = new ExecuteToolsNode(toolExecutor, limits);

    this.prepare = new PrepareWorkflowNode(compact, router);
    this.callModel =
        state ->
            new ProgressReportingNode(modelNode, "before_model", "after_model", progressListener)
                .apply(state);
    this.executeTools =
        state ->
            new ProgressReportingNode(toolsNode, "before_tools", "after_tools", progressListener)
                .apply(state);
    this.awaitApproval = new AwaitApprovalNode();
    this.routeExecution =
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
    this.planControl =
        new PlanControlNode(
            new CreatePlanNode(taskPlanner, planningClock, planProgressListener),
            new SelectPlanStepNode(planningClock, planProgressListener),
            new ExecutePlanStepNode(),
            new ReplanNode(taskPlanner, planningClock, planProgressListener));
    this.verify =
        new VerifyWorkflowNode(
            new VerifyPlanStepNode(planningClock, planProgressListener),
            new VerificationPipeline(List.of(new StateEvidenceVerifier())));
    FinishNode finishNode = new FinishNode(outputProtocols);
    this.finish = finishNode::apply;
  }

  /** Starts a new turn and runs until completion, cancellation, failure or approval pause. */
  public MiniClaudeState run(ModelRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    MiniClaudeState state = new MiniClaudeState(StateSchema.initialInput(request));
    this.middleware.beforeTurn(state);
    return drive(merge(state, apply(this.prepare, state)), CALL);
  }

  /** Continues a state that paused on an approval request. */
  public MiniClaudeState resume(MiniClaudeState paused, ApprovalDecision decision) {
    Objects.requireNonNull(paused, "paused must not be null");
    Objects.requireNonNull(decision, "decision must not be null");
    if (paused.status() != AgentStatus.WAITING_APPROVAL || paused.pendingApproval().isEmpty()) {
      throw new IllegalArgumentException("state is not waiting for approval");
    }
    if (!paused.pendingApproval().orElseThrow().approvalId().equals(decision.approvalId())) {
      throw new IllegalArgumentException("approval decision does not match pending request");
    }
    MiniClaudeState resumed =
        merge(
            paused,
            Map.of(
                MiniClaudeState.APPROVAL_DECISION,
                decision,
                MiniClaudeState.STATUS,
                AgentStatus.RUNNING));
    return drive(resumed, TOOLS);
  }

  private MiniClaudeState drive(MiniClaudeState initial, String initialRoute) {
    MiniClaudeState state = initial;
    String route = initialRoute;
    int maximumTransitions =
        4 * (this.limits.maxModelSteps() + 1) + this.limits.maxToolSteps() + 24;

    for (int transition = 0; transition < maximumTransitions; transition++) {
      switch (route) {
        case CALL -> {
          MiniClaudeState before = state;
          this.middleware.beforeModel(before);
          Map<String, Object> update = new LinkedHashMap<>(apply(this.callModel, before));
          this.middleware.afterModel(before, update);
          update.put(MiniClaudeState.WORKFLOW_SOURCE, "model");
          state = merge(before, update);
          if (state.pendingToolCalls().isEmpty() && reachedOutputLimit(state)) {
            state = prepareContinuation(state);
            route = CALL;
          } else {
            if (!state.continuationText().isEmpty()) {
              state =
                  merge(
                      state,
                      Map.of(
                          MiniClaudeState.FINAL_TEXT,
                          state.continuationText() + state.finalText(),
                          MiniClaudeState.CONTINUATION_TEXT,
                          ""));
            }
            route = state.pendingToolCalls().isEmpty() ? ROUTE : TOOLS;
          }
        }
        case TOOLS -> {
          MiniClaudeState before = state;
          this.middleware.beforeTools(before);
          Map<String, Object> update = new LinkedHashMap<>(apply(this.executeTools, before));
          this.middleware.afterTools(before, update);
          update.put(MiniClaudeState.WORKFLOW_SOURCE, "tools");
          state = merge(before, update);
          if (state.pendingApproval().isPresent()) {
            return merge(state, apply(this.awaitApproval, state));
          }
          route = ROUTE;
        }
        case ROUTE -> {
          state = merge(state, apply(this.routeExecution, state));
          route = state.workflowRoute();
        }
        case PLAN -> {
          state = merge(state, apply(this.planControl, state));
          route = state.workflowRoute();
        }
        case VERIFY -> {
          state = merge(state, apply(this.verify, state));
          route = state.workflowRoute();
        }
        case FINISH -> {
          state = merge(state, apply(this.finish, state));
          this.middleware.afterTurn(state);
          return state;
        }
        default -> {
          state =
              merge(
                  state,
                  Map.of(
                      MiniClaudeState.STATUS,
                      AgentStatus.FAILED,
                      MiniClaudeState.ERROR,
                      "unknown agent loop route: " + route));
          route = FINISH;
        }
      }
    }

    state =
        merge(
            state,
            Map.of(
                MiniClaudeState.STATUS,
                AgentStatus.FAILED,
                MiniClaudeState.ERROR,
                "agent loop transition limit exceeded"));
    state = merge(state, apply(this.finish, state));
    this.middleware.afterTurn(state);
    return state;
  }

  private static Map<String, Object> apply(
      AsyncNodeAction<MiniClaudeState> action, MiniClaudeState state) {
    try {
      return action.apply(state).join();
    } catch (CompletionException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      return Map.of(
          MiniClaudeState.STATUS,
          AgentStatus.FAILED,
          MiniClaudeState.ERROR,
          cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }
  }

  private static MiniClaudeState merge(MiniClaudeState state, Map<String, Object> update) {
    Map<String, Object> merged = new LinkedHashMap<>(state.data());
    Object previousTrace = merged.get(MiniClaudeState.TRACE);
    Object nextTrace = update.get(MiniClaudeState.TRACE);
    merged.putAll(update);
    if (previousTrace instanceof List<?> previous && nextTrace instanceof List<?> next) {
      List<Object> combined = new ArrayList<>(previous);
      combined.addAll(next);
      merged.put(MiniClaudeState.TRACE, List.copyOf(combined));
    }
    return new MiniClaudeState(Map.copyOf(merged));
  }

  private static boolean reachedOutputLimit(MiniClaudeState state) {
    Object value = state.providerMetadata().get("finishReason");
    if (!(value instanceof String reason)) {
      return false;
    }
    String normalized = reason.toLowerCase(Locale.ROOT).replace('-', '_');
    return normalized.equals("max_tokens")
        || normalized.equals("max_output_tokens")
        || normalized.equals("length");
  }

  private static MiniClaudeState prepareContinuation(MiniClaudeState state) {
    List<AgentMessage> messages = new ArrayList<>(state.messages());
    messages.add(
        new SystemMessage(
            "Continue exactly where the previous response stopped. Do not repeat completed text or"
                + " tool calls."));
    return merge(
        state,
        Map.of(
            MiniClaudeState.MESSAGES,
            List.copyOf(messages),
            MiniClaudeState.CONTINUATION_TEXT,
            state.continuationText() + state.finalText(),
            MiniClaudeState.CONTINUATION_COUNT,
            state.continuationCount() + 1,
            MiniClaudeState.FINAL_TEXT,
            ""));
  }
}
