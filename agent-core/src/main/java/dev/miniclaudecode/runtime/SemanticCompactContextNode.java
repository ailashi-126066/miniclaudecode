package dev.miniclaudecode.runtime;

import dev.miniclaudecode.context.ContextPlanner;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.runtime.recovery.RecoveryAttachment;
import dev.miniclaudecode.runtime.recovery.RecoveryAttachmentService;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** A graph-visible context compaction step with deterministic fallback. */
final class SemanticCompactContextNode implements AsyncNodeAction<MiniClaudeState> {
  private final SemanticContextCompactor compactor;
  private final TurnProgressListener listener;
  private final ContextPlanner planner = new ContextPlanner();
  private final RecoveryAttachmentService recovery = new RecoveryAttachmentService();

  SemanticCompactContextNode(
      dev.miniclaudecode.domain.model.ModelClient model, TurnProgressListener listener) {
    this.compactor = new SemanticContextCompactor(Objects.requireNonNull(model));
    this.listener = Objects.requireNonNull(listener, "listener must not be null");
  }

  @Override
  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    ContextPlanner.Plan before = plan(state);
    String reason =
        this.planner.isContextOverflow(state.failureType().orElse(""), state.error().orElse(""))
            ? "provider_overflow"
            : "preflight_threshold";
    RecoveryAttachment attachment = this.recovery.capture(state);
    return this.compactor
        .compact(state.request(), state.messages())
        .thenApply(
            compacted -> {
              List<AgentMessage> messages = attach(compacted, attachment);
              ContextPlanner.Plan after =
                  this.planner.plan(state.request(), messages, providerInputTokens(state));
              notify(
                  new TurnProgressListener.Progress(
                      "compaction",
                      state.modelSteps(),
                      state.toolSteps(),
                      state.compactionCount() + 1,
                      after.estimatedInputTokens(),
                      after.inputBudgetTokens(),
                      reason,
                      before.estimatedInputTokens(),
                      attachment.boundaryId()));
              Map<String, Object> update = new LinkedHashMap<>();
              update.put(MiniClaudeState.MESSAGES, messages);
              update.put(MiniClaudeState.COMPACTION_COUNT, state.compactionCount() + 1);
              update.put(MiniClaudeState.RECOVERY_ATTACHMENT, attachment);
              update.put(MiniClaudeState.COMPACT_BOUNDARY_ID, attachment.boundaryId());
              update.put(MiniClaudeState.TRACE, StateSchema.traceEntry("compact_context"));
              return Map.copyOf(update);
            });
  }

  private static List<AgentMessage> attach(
      List<AgentMessage> compacted, RecoveryAttachment attachment) {
    List<AgentMessage> messages = new ArrayList<>();
    for (AgentMessage message : compacted) {
      if (!(message instanceof SystemMessage system)
          || !system.text().startsWith("Structured recovery attachment [")) {
        messages.add(message);
      }
    }
    int insertion = !messages.isEmpty() && messages.getFirst() instanceof SystemMessage ? 1 : 0;
    messages.add(
        insertion,
        new SystemMessage(
            "Structured recovery attachment ["
                + attachment.boundaryId()
                + "]:\n"
                + attachment.toPromptText(RecoveryAttachmentService.DEFAULT_PROMPT_BUDGET)));
    return List.copyOf(messages);
  }

  private ContextPlanner.Plan plan(MiniClaudeState state) {
    return this.planner.plan(state.request(), state.messages(), providerInputTokens(state));
  }

  private static long providerInputTokens(MiniClaudeState state) {
    Object value = state.providerMetadata().get("inputTokens");
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private void notify(TurnProgressListener.Progress progress) {
    try {
      this.listener.onProgress(progress);
    } catch (RuntimeException ignored) {
      // Rendering or audit observers must not alter compaction semantics.
    }
  }
}
