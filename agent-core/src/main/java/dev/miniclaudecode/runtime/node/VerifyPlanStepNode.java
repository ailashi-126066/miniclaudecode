package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.planning.Plan;
import dev.miniclaudecode.planning.PlanStep;
import dev.miniclaudecode.planning.StepEvidence;
import dev.miniclaudecode.runtime.CompletionRequirements;
import dev.miniclaudecode.runtime.PlanProgressListener;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/** Deterministic evidence gate; semantic acceptance remains stated in the active step prompt. */
public final class VerifyPlanStepNode implements AsyncNodeAction<MiniClaudeState> {
  private final Clock clock;
  private final PlanProgressListener listener;

  public VerifyPlanStepNode(Clock clock, PlanProgressListener listener) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    Plan plan = state.plan().orElseThrow(() -> new IllegalStateException("no active Plan"));
    PlanStep step =
        plan.currentStep().orElseThrow(() -> new IllegalStateException("no active step"));
    List<ToolMessage> evidenceMessages = messagesForCurrentStep(state.messages());
    List<String> results = evidenceMessages.stream().map(ToolMessage::text).toList();
    List<String> verification =
        evidenceMessages.stream()
            .filter(tool -> tool.text().startsWith(ExecuteToolsNode.VERIFICATION_SUCCEEDED_PREFIX))
            .map(ToolMessage::text)
            .toList();
    List<String> changed =
        evidenceMessages.stream()
            .filter(tool -> isMutation(tool.qualifiedToolName()) && !tool.error())
            .map(ToolMessage::text)
            .toList();
    Optional<String> failure =
        evidenceMessages.stream().filter(ToolMessage::error).map(ToolMessage::text).findFirst();
    if (failure.isEmpty() && CompletionRequirements.requiresVerification(state)) {
      failure = Optional.of("Workspace changes have not passed a verification command");
    }
    if (failure.isEmpty() && state.finalText().isBlank()) {
      failure = Optional.of("Step produced no completion result");
    }
    StepEvidence evidence =
        new StepEvidence(results, verification, changed, failure, clock.instant());
    Map<String, Object> update = new LinkedHashMap<>();
    if (failure.isEmpty()) {
      Plan completed = plan.replaceStep(step.complete(evidence), clock.instant());
      update.put(MiniClaudeState.PLAN, completed);
      update.put(MiniClaudeState.STEP_DECISION, "COMPLETE");
      safelyNotify(
          completed.status() == dev.miniclaudecode.planning.PlanStatus.COMPLETED
              ? "PLAN_COMPLETED"
              : "PLAN_STEP_COMPLETED",
          completed);
    } else {
      Plan failed = plan.replaceStep(step.fail(evidence), clock.instant());
      update.put(MiniClaudeState.PLAN, failed);
      if (step.attempts() < maximumAttempts(state)) {
        update.put(MiniClaudeState.STEP_DECISION, "RETRY");
      } else if (plan.revisions() < maximumRevisions(state)) {
        update.put(MiniClaudeState.STEP_DECISION, "REPLAN");
      } else {
        Plan blocked = failed.block(clock.instant());
        update.put(MiniClaudeState.PLAN, blocked);
        update.put(MiniClaudeState.STEP_DECISION, "BLOCKED");
        update.put(MiniClaudeState.STATUS, AgentStatus.FAILED);
        update.put(MiniClaudeState.ERROR, failure.orElseThrow());
        safelyNotify("PLAN_BLOCKED", blocked);
      }
      safelyNotify("PLAN_STEP_FAILED", failed);
    }
    update.put(MiniClaudeState.TRACE, StateSchema.traceEntry("verify_step"));
    return CompletableFuture.completedFuture(Map.copyOf(update));
  }

  private static int maximumAttempts(MiniClaudeState state) {
    Object configured = state.request().attributes().get("planningMaxAttemptsPerStep");
    return configured instanceof Number number ? number.intValue() : 2;
  }

  private static int maximumRevisions(MiniClaudeState state) {
    Object configured = state.request().attributes().get("planningMaxRevisions");
    return configured instanceof Number number ? Math.min(1, number.intValue()) : 1;
  }

  private static List<ToolMessage> messagesForCurrentStep(List<AgentMessage> messages) {
    int marker = -1;
    for (int index = 0; index < messages.size(); index++) {
      if (messages.get(index) instanceof AgentMessage.SystemMessage system
          && system.text().startsWith("ACTIVE PLAN STEP ")) {
        marker = index;
      }
    }
    List<ToolMessage> found = new ArrayList<>();
    for (int index = marker + 1; index < messages.size(); index++) {
      if (messages.get(index) instanceof ToolMessage tool) {
        found.add(tool);
      }
    }
    return List.copyOf(found);
  }

  private static boolean isMutation(String name) {
    return "workspace:write".equals(name)
        || "workspace:edit".equals(name)
        || "workspace:apply_patch".equals(name);
  }

  private void safelyNotify(String event, Plan plan) {
    try {
      listener.onPlanChanged(event, plan);
    } catch (RuntimeException ignored) {
      // Best effort only.
    }
  }
}
