package dev.miniclaudecode.runtime;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolExecutionLedger;
import dev.miniclaudecode.domain.tool.ToolExecutionRecord;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LedgeredToolExecutor implements ToolExecutor {

  /**
   * Tools with no side effects, so an interrupted execution can simply be retried instead of asking
   * the user to confirm a possible external effect. Names must match the qualified form the
   * registry actually produces ({@code namespace:name}); the earlier dot-separated entries never
   * matched any registered tool, which meant {@code workspace:code_search} raised a HIGH-risk
   * confirmation prompt after any crash.
   */
  private static final Set<String> SAFE_RETRY_TOOLS =
      Set.of(
          "workspace:read",
          "workspace:list",
          "workspace:glob",
          "workspace:grep",
          "workspace:code_search",
          "context:read_result",
          "agent:delegate",
          "memory:search",
          "session:search",
          "skills:route_skill",
          "skills:load_skill",
          "task:todo");

  /**
   * These tools have no side effects and are independent of one another, so a model batch can run
   * them concurrently. Keep this narrower than {@link #SAFE_RETRY_TOOLS}: retry safety alone does
   * not make a tool suitable for concurrent execution.
   */
  private static final Set<String> CONCURRENT_READ_ONLY_TOOLS =
      Set.of(
          "workspace:read",
          "workspace:list",
          "workspace:glob",
          "workspace:grep",
          "workspace:code_search");

  private final ToolExecutor delegate;
  private final ToolExecutionLedger ledger;
  private final Clock clock;

  public LedgeredToolExecutor(ToolExecutor delegate, ToolExecutionLedger ledger, Clock clock) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public CompletionStage<List<ToolResult>> execute(List<ToolCall> calls) {
    return execute(calls, Optional.empty(), Optional.empty());
  }

  @Override
  public CompletionStage<List<ToolResult>> execute(
      List<ToolCall> calls,
      Optional<ApprovalRequest> pendingApproval,
      Optional<ApprovalDecision> approvalDecision) {
    Objects.requireNonNull(calls, "calls must not be null");
    Objects.requireNonNull(pendingApproval, "pendingApproval must not be null");
    Objects.requireNonNull(approvalDecision, "approvalDecision must not be null");
    List<ToolCall> orderedCalls = List.copyOf(calls);
    CompletionStage<List<ToolResult>> chain = CompletableFuture.completedFuture(new ArrayList<>());
    for (int index = 0; index < orderedCalls.size(); ) {
      ToolCall call = orderedCalls.get(index);
      if (isConcurrentReadOnly(call)) {
        int end = index + 1;
        while (end < orderedCalls.size() && isConcurrentReadOnly(orderedCalls.get(end))) {
          end++;
        }
        List<ToolCall> batch = orderedCalls.subList(index, end);
        chain =
            chain.thenCompose(
                accumulated -> {
                  if (awaitingApproval(accumulated)) {
                    return CompletableFuture.completedFuture(accumulated);
                  }
                  return executeReadOnlyBatch(batch, pendingApproval, approvalDecision)
                      .thenApply(
                          results -> {
                            accumulated.addAll(results);
                            return accumulated;
                          });
                });
        index = end;
      } else {
        chain =
            chain.thenCompose(
                accumulated -> {
                  // Stop at the first approval request: later calls must not run under an
                  // approval decision that was issued only for an earlier tool call.
                  if (awaitingApproval(accumulated)) {
                    return CompletableFuture.completedFuture(accumulated);
                  }
                  return executeOne(call, pendingApproval, approvalDecision)
                      .thenApply(
                          result -> {
                            accumulated.add(result);
                            return accumulated;
                          });
                });
        index++;
      }
    }
    return chain.thenApply(List::copyOf);
  }

  private CompletionStage<List<ToolResult>> executeReadOnlyBatch(
      List<ToolCall> calls,
      Optional<ApprovalRequest> pendingApproval,
      Optional<ApprovalDecision> approvalDecision) {
    List<CompletableFuture<ToolResult>> futures =
        calls.stream()
            .map(call -> executeOne(call, pendingApproval, approvalDecision).toCompletableFuture())
            .toList();
    CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(all)
        .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
  }

  private static boolean isConcurrentReadOnly(ToolCall call) {
    return CONCURRENT_READ_ONLY_TOOLS.contains(call.qualifiedName());
  }

  private static boolean awaitingApproval(List<ToolResult> results) {
    return results.stream()
        .anyMatch(result -> result.status() == ToolResult.Status.APPROVAL_REQUIRED);
  }

  private CompletionStage<ToolResult> executeOne(
      ToolCall call,
      Optional<ApprovalRequest> batchApproval,
      Optional<ApprovalDecision> batchDecision) {
    // An approval request and its decision are scoped to exactly one tool call. Forwarding a
    // decision to a call it was not issued for makes tools reject it as a tampered binding, so the
    // decision travels only alongside its own request.
    Optional<ApprovalRequest> pendingApproval = approvalFor(call, batchApproval);
    Optional<ApprovalDecision> approvalDecision =
        pendingApproval.isPresent() ? batchDecision : Optional.empty();
    Optional<ToolExecutionRecord> existing = findRecord(call.toolCallId());
    if (existing
        .filter(record -> record.status() == ToolExecutionRecord.Status.COMPLETED)
        .isPresent()) {
      ToolExecutionRecord completed = existing.orElseThrow();
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              "Reused completed tool execution: " + call.qualifiedName(),
              completed.resultReference(),
              Map.of("ledgerReused", true)));
    }
    if (existing.filter(record -> isUncertain(record.status())).isPresent() && !isSafeRetry(call)) {
      Optional<ToolResult> confirmation =
          requireUncertainEffectConfirmation(call, pendingApproval, approvalDecision);
      if (confirmation.isPresent()) {
        return CompletableFuture.completedFuture(confirmation.orElseThrow());
      }
    }

    RiskLevel riskLevel = classify(call);
    ToolExecutionRecord pending =
        new ToolExecutionRecord(
            call.toolCallId(),
            call.qualifiedName(),
            ToolExecutionRecord.Status.PENDING,
            riskLevel,
            existing.flatMap(ToolExecutionRecord::beforeHash),
            Optional.empty(),
            Optional.empty(),
            clock.instant());
    saveRecord(pending);
    CompletionStage<List<ToolResult>> execution;
    try {
      execution = delegate.execute(List.of(call), pendingApproval, approvalDecision);
    } catch (RuntimeException error) {
      markInterrupted(pending, call);
      return CompletableFuture.failedFuture(error);
    }
    return execution
        .thenApply(
            results -> {
              ToolResult result = singleResult(call, results);
              recordResult(pending, result);
              return result;
            })
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                markInterrupted(pending, call);
              }
            });
  }

  private Optional<ToolResult> requireUncertainEffectConfirmation(
      ToolCall call,
      Optional<ApprovalRequest> pendingApproval,
      Optional<ApprovalDecision> approvalDecision) {
    if (approvalDecision.isEmpty()) {
      ApprovalRequest request =
          new ApprovalRequest(
              UUID.randomUUID(),
              call,
              RiskLevel.HIGH,
              call.qualifiedName(),
              "A previous process stopped while this tool might have been executing. "
                  + "Confirm before retrying a possible external side effect.",
              Optional.empty(),
              Optional.empty(),
              clock.instant());
      saveRecord(
          new ToolExecutionRecord(
              call.toolCallId(),
              call.qualifiedName(),
              ToolExecutionRecord.Status.UNKNOWN,
              RiskLevel.HIGH,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              clock.instant()));
      return Optional.of(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.APPROVAL_REQUIRED,
              "Confirmation required for uncertain prior execution",
              Optional.empty(),
              Map.of("approvalRequest", request)));
    }
    ApprovalDecision decision = approvalDecision.orElseThrow();
    boolean matches =
        pendingApproval
            .filter(request -> request.toolCall().toolCallId().equals(call.toolCallId()))
            .filter(request -> request.approvalId().equals(decision.approvalId()))
            .isPresent();
    if (!matches || decision.choice() == ApprovalDecision.Choice.REJECT) {
      return Optional.of(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.CANCELLED,
              "Uncertain tool execution was not retried",
              Optional.empty(),
              Map.of("ledgerReused", false)));
    }
    return Optional.empty();
  }

  private void recordResult(ToolExecutionRecord pending, ToolResult result) {
    if (result.status() == ToolResult.Status.APPROVAL_REQUIRED) {
      saveRecord(
          new ToolExecutionRecord(
              pending.toolCallId(),
              pending.qualifiedToolName(),
              ToolExecutionRecord.Status.AWAITING_APPROVAL,
              pending.riskLevel(),
              pending.beforeHash(),
              Optional.empty(),
              Optional.empty(),
              clock.instant()));
      return;
    }
    ToolExecutionRecord.Status status =
        result.status() == ToolResult.Status.COMPLETED
            ? ToolExecutionRecord.Status.COMPLETED
            : ToolExecutionRecord.Status.FAILED;
    saveRecord(
        new ToolExecutionRecord(
            pending.toolCallId(),
            pending.qualifiedToolName(),
            status,
            pending.riskLevel(),
            metadataText(result, "beforeHash").or(() -> pending.beforeHash()),
            metadataText(result, "afterHash"),
            result.resultReference(),
            clock.instant()));
  }

  private void markInterrupted(ToolExecutionRecord pending, ToolCall call) {
    saveRecord(
        new ToolExecutionRecord(
            pending.toolCallId(),
            pending.qualifiedToolName(),
            isSafeRetry(call)
                ? ToolExecutionRecord.Status.PENDING
                : ToolExecutionRecord.Status.UNKNOWN,
            pending.riskLevel(),
            pending.beforeHash(),
            Optional.empty(),
            Optional.empty(),
            clock.instant()));
  }

  private static ToolResult singleResult(ToolCall call, List<ToolResult> results) {
    Objects.requireNonNull(results, "tool results must not be null");
    return results.stream()
        .filter(result -> result.toolCallId().equals(call.toolCallId()))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "tool executor returned no result for " + call.toolCallId()));
  }

  private static Optional<ApprovalRequest> approvalFor(
      ToolCall call, Optional<ApprovalRequest> approval) {
    return approval.filter(request -> request.toolCall().toolCallId().equals(call.toolCallId()));
  }

  private static Optional<String> metadataText(ToolResult result, String key) {
    Object value = result.metadata().get(key);
    return value instanceof String text && !text.isBlank()
        ? Optional.of(text.trim())
        : Optional.empty();
  }

  private synchronized Optional<ToolExecutionRecord> findRecord(String toolCallId) {
    return this.ledger.find(toolCallId);
  }

  private synchronized void saveRecord(ToolExecutionRecord record) {
    this.ledger.save(record);
  }

  private static boolean isUncertain(ToolExecutionRecord.Status status) {
    return status == ToolExecutionRecord.Status.PENDING
        || status == ToolExecutionRecord.Status.UNKNOWN;
  }

  private static boolean isSafeRetry(ToolCall call) {
    return SAFE_RETRY_TOOLS.contains(call.qualifiedName());
  }

  private static RiskLevel classify(ToolCall call) {
    if (isSafeRetry(call)) {
      return RiskLevel.LOW;
    }
    // Workspace mutations are recoverable (the file is still on disk and hashed); anything that can
    // reach outside the workspace — shell, web, MCP — is not.
    if (call.qualifiedName().startsWith("workspace:")) {
      return RiskLevel.MEDIUM;
    }
    return RiskLevel.HIGH;
  }
}
