package dev.miniclaudecode.runtime;

import dev.miniclaudecode.context.ContextPipeline;
import dev.miniclaudecode.context.ContextPlanner;
import dev.miniclaudecode.context.DeterministicContextReducer;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.runtime.node.CallModelNode;
import dev.miniclaudecode.runtime.node.CompactContextNode;
import dev.miniclaudecode.runtime.node.ExecuteToolsNode;
import dev.miniclaudecode.runtime.node.FinishNode;
import dev.miniclaudecode.runtime.node.PrepareContextNode;
import dev.miniclaudecode.runtime.node.RecoverErrorNode;
import dev.miniclaudecode.runtime.node.RepairOutputNode;
import dev.miniclaudecode.runtime.node.RequireVerificationNode;
import dev.miniclaudecode.runtime.output.EngineeringReportValidator;
import dev.miniclaudecode.runtime.output.OutputProtocol;
import dev.miniclaudecode.runtime.output.OutputProtocolRegistry;
import dev.miniclaudecode.runtime.output.RagCitationValidator;
import dev.miniclaudecode.runtime.retry.RetryPolicy;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.action.AsyncNodeAction;

/** The ordinary model/tool turn. The graph is used only when this loop pauses for approval. */
final class NormalTurnLoop implements AsyncNodeAction<MiniClaudeState> {
  private static final int DEFAULT_MAX_COMPACTIONS = 3;
  private final ContextPlanner contextPlanner = new ContextPlanner();
  private final RetryPolicy retryPolicy = new RetryPolicy();
  private final OutputProtocolRegistry outputProtocols = new OutputProtocolRegistry();
  private final EngineeringReportValidator engineeringReportValidator =
      new EngineeringReportValidator();
  private final RagCitationValidator citationValidator = new RagCitationValidator();
  private final PrepareContextNode prepare = new PrepareContextNode();
  private final CallModelNode callModel;
  private final CompactContextNode compact =
      new CompactContextNode(new ContextPipeline(List.of(new DeterministicContextReducer())));
  private final SemanticContextCompactor semanticCompactor;
  private final RecoverErrorNode recover = new RecoverErrorNode(this.retryPolicy);
  private final ExecuteToolsNode executeTools;
  private final RequireVerificationNode requireVerification = new RequireVerificationNode();
  private final RepairOutputNode repair = new RepairOutputNode(this.outputProtocols);
  private final FinishNode finish = new FinishNode(this.outputProtocols);
  private final TurnProgressListener progressListener;

  NormalTurnLoop(
      dev.miniclaudecode.domain.model.ModelClient model,
      ToolExecutor tools,
      TurnLimits limits,
      dev.miniclaudecode.domain.runtime.CancellationToken cancellationToken,
      TurnProgressListener progressListener) {
    this.callModel = new CallModelNode(model, limits, cancellationToken);
    this.semanticCompactor = new SemanticContextCompactor(model);
    this.executeTools = new ExecuteToolsNode(tools, limits);
    this.progressListener = progressListener;
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState initial) {
    try {
      MiniClaudeState state = initial;
      Map<String, Object> changes = new LinkedHashMap<>();
      if (state.modelSteps() == 0 && state.pendingApproval().isEmpty()) {
        state = apply(state, this.prepare.apply(state).join(), changes);
      }
      while (true) {
        if (!state.pendingToolCalls().isEmpty()) {
          notifyStage("before_tools", state);
          state = apply(state, this.executeTools.apply(state).join(), changes);
          notifyStage("after_tools", state);
          if (state.status() == AgentStatus.WAITING_APPROVAL) {
            return CompletableFuture.completedFuture(changes);
          }
          if (state.error().isPresent()) {
            apply(state, this.finish.apply(state).join(), changes);
            return CompletableFuture.completedFuture(Map.copyOf(changes));
          }
          continue;
        }
        ContextPlanner.Plan plan = plan(state);
        if (plan.compact() && canCompact(state)) {
          state = compact(state, changes, plan, "preflight_threshold");
          continue;
        }
        notifyStage("before_model", state, plan);
        state = apply(state, this.callModel.apply(state).join(), changes);
        notifyStage("after_model", state);
        if (state.status() == AgentStatus.CANCELLED) {
          apply(state, this.finish.apply(state).join(), changes);
          return CompletableFuture.completedFuture(Map.copyOf(changes));
        }
        if (state.error().isPresent()) {
          if (this.contextPlanner.isContextOverflow(
                  state.failureType().orElse(""), state.error().orElse(""))
              && canCompact(state)) {
            state = compact(state, changes, plan(state), "provider_overflow");
            continue;
          }
          RetryPolicy.Decision retry =
              this.retryPolicy.decide(
                  state.failureType().orElse(""),
                  state.failureRetryable(),
                  state.retryCount(),
                  Optional.empty(),
                  maximumRetries(state));
          if (retry.retry()) {
            state = apply(state, this.recover.apply(state).join(), changes);
            continue;
          }
          apply(state, this.finish.apply(state).join(), changes);
          return CompletableFuture.completedFuture(Map.copyOf(changes));
        }
        if (!state.pendingToolCalls().isEmpty()) {
          continue;
        }
        if (requiresVerification(state) || hasIncompleteTasks(state)) {
          if (state.verificationPrompts() >= 2) {
            state =
                apply(
                    state,
                    Map.of(
                        MiniClaudeState.ERROR,
                        "Verification is required after workspace changes, but no successful "
                            + "verification command was recorded.",
                        MiniClaudeState.STATUS,
                        AgentStatus.FAILED,
                        MiniClaudeState.TRACE,
                        List.of("verification_gate_failed")),
                    changes);
            apply(state, this.finish.apply(state).join(), changes);
            return CompletableFuture.completedFuture(Map.copyOf(changes));
          }
          state = apply(state, this.requireVerification.apply(state).join(), changes);
          continue;
        }
        OutputProtocol.Evaluation output =
            this.outputProtocols.evaluate(state.request(), state.finalText());
        if (!output.valid()
            && state.outputRepairCount() < this.outputProtocols.maximumRepairs(state.request())) {
          state = apply(state, this.repair.apply(state).join(), changes);
          continue;
        }
        RagCitationValidator.Evaluation citations =
            this.citationValidator.evaluate(state.request(), state.messages(), state.finalText());
        if (!citations.valid()
            && state.outputRepairCount() < this.outputProtocols.maximumRepairs(state.request())) {
          state =
              apply(state, this.repair.apply(state, citations.repairInstruction()).join(), changes);
          continue;
        }
        EngineeringReportValidator.Evaluation report =
            this.engineeringReportValidator.evaluate(state.messages(), state.finalText());
        if (!report.valid()
            && state.outputRepairCount() < this.outputProtocols.maximumRepairs(state.request())) {
          state =
              apply(state, this.repair.apply(state, report.repairInstruction()).join(), changes);
          continue;
        }
        apply(state, this.finish.apply(state).join(), changes);
        return CompletableFuture.completedFuture(Map.copyOf(changes));
      }
    } catch (RuntimeException error) {
      return CompletableFuture.failedFuture(error);
    }
  }

  private static MiniClaudeState apply(
      MiniClaudeState state, Map<String, Object> update, Map<String, Object> changes) {
    Map<String, Object> data = new LinkedHashMap<>(state.data());
    Map<String, Object> changed = new LinkedHashMap<>(update);
    if (update.get("trace") instanceof List<?> entries) {
      List<Object> trace = new ArrayList<>(state.trace());
      trace.addAll(entries);
      data.put("trace", List.copyOf(trace));
      List<Object> previous =
          changes.get("trace") instanceof List<?> existing
              ? new ArrayList<>(existing)
              : new ArrayList<>();
      previous.addAll(entries);
      changed.put("trace", List.copyOf(previous));
    }
    update.forEach(
        (key, value) -> {
          if (!"trace".equals(key)) {
            data.put(key, value);
          }
        });
    changes.putAll(changed);
    return new MiniClaudeState(Map.copyOf(data));
  }

  private static int maximumRetries(MiniClaudeState state) {
    Object configured = state.request().attributes().get("maxRetries");
    return configured instanceof Number number ? number.intValue() : 3;
  }

  private MiniClaudeState compact(
      MiniClaudeState state,
      Map<String, Object> changes,
      ContextPlanner.Plan before,
      String reason) {
    MiniClaudeState compacted =
        apply(
            state,
            Map.of(
                "messages",
                this.semanticCompactor.compact(state.request(), state.messages()).join(),
                "compactionCount",
                state.compactionCount() + 1,
                "trace",
                dev.miniclaudecode.runtime.state.StateSchema.traceEntry("compact_context")),
            changes);
    ContextPlanner.Plan after = plan(compacted);
    notify(
        new TurnProgressListener.Progress(
            "compaction",
            compacted.modelSteps(),
            compacted.toolSteps(),
            compacted.compactionCount(),
            after.estimatedInputTokens(),
            after.inputBudgetTokens(),
            reason,
            before.estimatedInputTokens()));
    return compacted;
  }

  private static boolean canCompact(MiniClaudeState state) {
    Object configured = state.request().attributes().get("maxCompactions");
    int maximum = configured instanceof Number number ? number.intValue() : DEFAULT_MAX_COMPACTIONS;
    return maximum > 0 && state.compactionCount() < maximum;
  }

  private void notifyStage(String phase, MiniClaudeState state) {
    notifyStage(phase, state, plan(state));
  }

  private void notifyStage(String phase, MiniClaudeState state, ContextPlanner.Plan plan) {
    notify(
        new TurnProgressListener.Progress(
            phase,
            state.modelSteps(),
            state.toolSteps(),
            state.compactionCount(),
            plan.estimatedInputTokens(),
            plan.inputBudgetTokens(),
            "",
            0));
  }

  private void notify(TurnProgressListener.Progress progress) {
    try {
      this.progressListener.onProgress(progress);
    } catch (RuntimeException ignored) {
      // Progress persistence and rendering must never change the model/tool safety semantics.
    }
  }

  private ContextPlanner.Plan plan(MiniClaudeState state) {
    Object value = state.providerMetadata().get("inputTokens");
    long providerTokens = value instanceof Number number ? number.longValue() : 0L;
    return this.contextPlanner.plan(state.request(), state.messages(), providerTokens);
  }

  static boolean requiresVerification(MiniClaudeState state) {
    if (!Boolean.TRUE.equals(state.request().attributes().get("requireVerification"))) {
      return false;
    }
    int lastMutation = -1;
    int lastSuccessfulVerification = -1;
    List<AgentMessage> messages = state.messages();
    for (int index = 0; index < messages.size(); index++) {
      if (messages.get(index) instanceof ToolMessage tool) {
        if (isMutation(tool.qualifiedToolName()) && !tool.error()) {
          lastMutation = index;
        } else if ("shell:run".equals(tool.qualifiedToolName())
            && !tool.error()
            && tool.text().startsWith(ExecuteToolsNode.VERIFICATION_SUCCEEDED_PREFIX)) {
          lastSuccessfulVerification = index;
        }
      }
    }
    return lastMutation > lastSuccessfulVerification;
  }

  static boolean hasIncompleteTasks(MiniClaudeState state) {
    if (!Boolean.TRUE.equals(state.request().attributes().get("requireTaskCompletion"))) {
      return false;
    }
    ToolMessage latest = null;
    for (AgentMessage message : state.messages()) {
      if (message instanceof ToolMessage tool
          && "task:todo".equals(tool.qualifiedToolName())
          && !tool.error()) {
        latest = tool;
      }
    }
    return latest != null && (latest.text().contains("[ ]") || latest.text().contains("[>]"));
  }

  private static boolean isMutation(String name) {
    return "workspace:write".equals(name)
        || "workspace:edit".equals(name)
        || "workspace:apply_patch".equals(name);
  }
}
