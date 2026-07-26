package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolExecutionLedger;
import dev.miniclaudecode.domain.tool.ToolExecutionRecord;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Models routinely emit several tool calls in one assistant message. Before these fixes that path
 * was broken in three separate ways: the batch did not stop at the first call needing approval, the
 * approval decision was forwarded to every call in the batch regardless of which request it was
 * issued for, and the graph node carried only one approval request so the rest were dropped.
 */
class ParallelToolApprovalTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("the batch stops at the first call that needs a decision")
  void batchShortCircuitsOnTheFirstApproval() {
    List<String> executed = new ArrayList<>();
    ToolCall edit = new ToolCall("call-edit", "workspace:edit", "{}");
    ToolCall write = new ToolCall("call-write", "workspace:write", "{}");

    LedgeredToolExecutor executor =
        new LedgeredToolExecutor(
            recording(executed, call -> approvalRequired(call)), new InMemoryLedger(), CLOCK);

    List<ToolResult> results =
        executor
            .execute(List.of(edit, write), Optional.empty(), Optional.empty())
            .toCompletableFuture()
            .join();

    // Only the first call ran; the second is left for the next pass, so its approval request
    // cannot be silently discarded by the caller.
    assertThat(executed).containsExactly("call-edit");
    assertThat(results).hasSize(1);
    assertThat(results.getFirst().status()).isEqualTo(ToolResult.Status.APPROVAL_REQUIRED);
  }

  @Test
  @DisplayName("an approval decision is never applied to a call it was not issued for")
  void approvalDecisionIsScopedToItsOwnCall() {
    ToolCall approved = new ToolCall("call-approved", "workspace:edit", "{}");
    ToolCall other = new ToolCall("call-other", "workspace:edit", "{}");
    ApprovalRequest request =
        new ApprovalRequest(
            UUID.randomUUID(),
            approved,
            RiskLevel.MEDIUM,
            "A.java",
            "apply diff",
            Optional.of("before"),
            Optional.of("diff"),
            CLOCK.instant());
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(),
            ApprovalDecision.Choice.ALLOW,
            ApprovalDecision.Scope.ONCE,
            Optional.empty(),
            CLOCK.instant());

    Map<String, Optional<ApprovalDecision>> seen = new LinkedHashMap<>();
    ToolExecutor delegate =
        new ToolExecutor() {
          @Override
          public CompletionStage<List<ToolResult>> execute(List<ToolCall> calls) {
            return execute(calls, Optional.empty(), Optional.empty());
          }

          @Override
          public CompletionStage<List<ToolResult>> execute(
              List<ToolCall> calls,
              Optional<ApprovalRequest> pending,
              Optional<ApprovalDecision> supplied) {
            ToolCall call = calls.getFirst();
            seen.put(call.toolCallId(), supplied);
            // Mirrors what the real tools do: a decision with no matching request is a tampered
            // binding and must not be honoured.
            if (supplied.isPresent() && pending.isEmpty()) {
              throw new IllegalArgumentException(
                  "approval request and decision must be supplied together");
            }
            return CompletableFuture.completedFuture(
                List.of(supplied.isPresent() ? completed(call) : approvalRequired(call)));
          }
        };

    List<ToolResult> results =
        new LedgeredToolExecutor(delegate, new InMemoryLedger(), CLOCK)
            .execute(List.of(approved, other), Optional.of(request), Optional.of(decision))
            .toCompletableFuture()
            .join();

    assertThat(seen.get("call-approved")).contains(decision);
    assertThat(results.getFirst().status()).isEqualTo(ToolResult.Status.COMPLETED);
    // The second call is reached only because the first no longer needs approval; it must be
    // offered a clean slate rather than another call's decision.
    if (seen.containsKey("call-other")) {
      assertThat(seen.get("call-other")).isEmpty();
    }
  }

  @Test
  @DisplayName("read-only tools use their real qualified names in the safe-retry allowlist")
  void safeRetryAllowlistMatchesRegisteredToolNames() {
    InMemoryLedger ledger = new InMemoryLedger();
    ledger.save(
        new ToolExecutionRecord(
            "call-search",
            "workspace:code_search",
            ToolExecutionRecord.Status.PENDING,
            RiskLevel.LOW,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            CLOCK.instant()));
    List<String> executed = new ArrayList<>();

    ToolResult result =
        new LedgeredToolExecutor(
                recording(executed, ParallelToolApprovalTest::completed), ledger, CLOCK)
            .execute(List.of(new ToolCall("call-search", "workspace:code_search", "{}")))
            .toCompletableFuture()
            .join()
            .getFirst();

    // A read-only search interrupted by a crash is simply retried; it must not raise the
    // "a previous process stopped while this tool might have been executing" confirmation.
    assertThat(executed).containsExactly("call-search");
    assertThat(result.status()).isEqualTo(ToolResult.Status.COMPLETED);
  }

  private static ToolExecutor recording(
      List<String> executed, java.util.function.Function<ToolCall, ToolResult> outcome) {
    return new ToolExecutor() {
      @Override
      public CompletionStage<List<ToolResult>> execute(List<ToolCall> calls) {
        return execute(calls, Optional.empty(), Optional.empty());
      }

      @Override
      public CompletionStage<List<ToolResult>> execute(
          List<ToolCall> calls,
          Optional<ApprovalRequest> pending,
          Optional<ApprovalDecision> decision) {
        ToolCall call = calls.getFirst();
        executed.add(call.toolCallId());
        return CompletableFuture.completedFuture(List.of(outcome.apply(call)));
      }
    };
  }

  private static ToolResult approvalRequired(ToolCall call) {
    ApprovalRequest request =
        new ApprovalRequest(
            UUID.randomUUID(),
            call,
            RiskLevel.MEDIUM,
            call.toolCallId(),
            "needs approval",
            Optional.of("before"),
            Optional.of("diff"),
            CLOCK.instant());
    return new ToolResult(
        call.toolCallId(),
        ToolResult.Status.APPROVAL_REQUIRED,
        "approval required",
        Optional.empty(),
        Map.of("approvalRequest", request));
  }

  private static ToolResult completed(ToolCall call) {
    return new ToolResult(
        call.toolCallId(),
        ToolResult.Status.COMPLETED,
        "done " + call.toolCallId(),
        Optional.empty(),
        Map.of());
  }

  private static final class InMemoryLedger implements ToolExecutionLedger {
    private final Map<String, ToolExecutionRecord> records = new LinkedHashMap<>();

    @Override
    public Optional<ToolExecutionRecord> find(String toolCallId) {
      return Optional.ofNullable(records.get(toolCallId));
    }

    @Override
    public List<ToolExecutionRecord> list() {
      return new ArrayList<>(records.values());
    }

    @Override
    public void save(ToolExecutionRecord record) {
      records.put(record.toolCallId(), record);
    }
  }
}
