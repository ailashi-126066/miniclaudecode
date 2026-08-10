package dev.miniclaudecode.runtime.route;

import dev.miniclaudecode.context.ContextPlanner;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.CompletionRequirements;
import dev.miniclaudecode.runtime.output.EngineeringReportValidator;
import dev.miniclaudecode.runtime.output.OutputProtocol;
import dev.miniclaudecode.runtime.output.OutputProtocolRegistry;
import dev.miniclaudecode.runtime.output.RagCitationValidator;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncEdgeAction;

/** Centralizes graph transitions while leaving every material operation as its own node. */
public final class ResponseRouter {
  private static final int DEFAULT_MAX_COMPACTIONS = 3;
  private final ContextPlanner planner;
  private final RetryPolicy retryPolicy;
  private final OutputProtocolRegistry protocols;
  private final RagCitationValidator citations;
  private final EngineeringReportValidator reports;

  public ResponseRouter(
      ContextPlanner planner,
      RetryPolicy retryPolicy,
      OutputProtocolRegistry protocols,
      RagCitationValidator citations,
      EngineeringReportValidator reports) {
    this.planner = Objects.requireNonNull(planner, "planner must not be null");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    this.protocols = Objects.requireNonNull(protocols, "protocols must not be null");
    this.citations = Objects.requireNonNull(citations, "citations must not be null");
    this.reports = Objects.requireNonNull(reports, "reports must not be null");
  }

  public AsyncEdgeAction<MiniClaudeState> afterPrepare() {
    return state -> CompletableFuture.completedFuture(routeContext(state));
  }

  public AsyncEdgeAction<MiniClaudeState> afterModel() {
    return state -> CompletableFuture.completedFuture(routeAfterModel(state));
  }

  public AsyncEdgeAction<MiniClaudeState> afterTools() {
    return state ->
        CompletableFuture.completedFuture(
            state.error().isPresent()
                ? "finish"
                : state.pendingApproval().isPresent()
                    ? "approval"
                    : planningRequested(state) ? "create_plan" : routeContext(state));
  }

  public AsyncEdgeAction<MiniClaudeState> afterSelectStep() {
    return state ->
        CompletableFuture.completedFuture(
            switch (state.planningPhase()) {
              case "EXECUTE_STEP" -> "execute";
              case "FINAL_VERIFICATION" -> "final_verify";
              default -> "finish";
            });
  }

  public AsyncEdgeAction<MiniClaudeState> afterVerifyStep() {
    return state ->
        CompletableFuture.completedFuture(
            switch (state.stepDecision()) {
              case "COMPLETE", "RETRY" -> "select";
              case "REPLAN" -> "replan";
              default -> "finish";
            });
  }

  private String routeAfterModel(MiniClaudeState state) {
    if (state.status() == AgentStatus.CANCELLED) {
      return "finish";
    }
    if (state.error().isPresent()) {
      if (isOverflow(state) && canCompact(state)) {
        return "compact";
      }
      RetryPolicy.Decision decision =
          this.retryPolicy.decide(
              state.failureType().orElse(""),
              state.failureRetryable(),
              state.retryCount(),
              Optional.empty(),
              maximumRetries(state));
      return decision.retry() ? "retry" : "finish";
    }
    if (!state.pendingToolCalls().isEmpty()) {
      return "tools";
    }
    if (state.plan().flatMap(dev.miniclaudecode.planning.Plan::currentStep).isPresent()) {
      return "verify_step";
    }
    if (CompletionRequirements.requiresVerification(state)) {
      return state.verificationPrompts() < 2 ? "verify" : "invalid";
    }
    OutputProtocol.Evaluation output = this.protocols.evaluate(state.request(), state.finalText());
    if (!output.valid()) {
      return canRepair(state) ? "repair_output" : "finish";
    }
    RagCitationValidator.Evaluation citation =
        this.citations.evaluate(state.request(), state.messages(), state.finalText());
    if (!citation.valid()) {
      return canRepair(state) ? "repair_citations" : "invalid";
    }
    EngineeringReportValidator.Evaluation report =
        this.reports.evaluate(state.messages(), state.finalText());
    if (!report.valid()) {
      return canRepair(state) ? "repair_report" : "invalid";
    }
    return "finish";
  }

  private static boolean planningRequested(MiniClaudeState state) {
    return state.plan().isEmpty()
        && state.toolResults().stream()
            .anyMatch(result -> Boolean.TRUE.equals(result.metadata().get("planningRequested")));
  }

  private String routeContext(MiniClaudeState state) {
    return plan(state).compact() && canCompact(state) ? "compact" : "model";
  }

  private ContextPlanner.Plan plan(MiniClaudeState state) {
    Object value = state.providerMetadata().get("inputTokens");
    long providerTokens = value instanceof Number number ? number.longValue() : 0L;
    return this.planner.plan(state.request(), state.messages(), providerTokens);
  }

  private boolean isOverflow(MiniClaudeState state) {
    return this.planner.isContextOverflow(state.failureType().orElse(""), state.error().orElse(""));
  }

  private boolean canRepair(MiniClaudeState state) {
    return state.outputRepairCount() < this.protocols.maximumRepairs(state.request());
  }

  private static boolean canCompact(MiniClaudeState state) {
    Object configured = state.request().attributes().get("maxCompactions");
    int maximum = configured instanceof Number number ? number.intValue() : DEFAULT_MAX_COMPACTIONS;
    return maximum > 0 && state.compactionCount() < maximum;
  }

  private static int maximumRetries(MiniClaudeState state) {
    Object configured = state.request().attributes().get("maxRetries");
    return configured instanceof Number number ? number.intValue() : 3;
  }
}
