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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LedgeredToolExecutorTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void reusesACompletedCallWithoutRepeatingItsSideEffect() {
    InMemoryLedger ledger = new InMemoryLedger();
    ledger.save(record("shell:run", ToolExecutionRecord.Status.COMPLETED));
    AtomicInteger executions = new AtomicInteger();
    ToolExecutor delegate =
        calls -> {
          executions.incrementAndGet();
          return CompletableFuture.completedFuture(List.of(completed(calls.getFirst())));
        };
    LedgeredToolExecutor executor = new LedgeredToolExecutor(delegate, ledger, CLOCK);

    List<ToolResult> results =
        executor.execute(List.of(call("shell:run"))).toCompletableFuture().join();

    assertThat(executions).hasValue(0);
    assertThat(results.getFirst().metadata()).containsEntry("ledgerReused", true);
  }

  @Test
  void retriesAnInterruptedReadBecauseItHasNoExternalSideEffect() {
    InMemoryLedger ledger = new InMemoryLedger();
    ledger.save(record("workspace:read", ToolExecutionRecord.Status.PENDING));
    AtomicInteger executions = new AtomicInteger();
    ToolExecutor delegate =
        calls -> {
          executions.incrementAndGet();
          return CompletableFuture.completedFuture(List.of(completed(calls.getFirst())));
        };
    LedgeredToolExecutor executor = new LedgeredToolExecutor(delegate, ledger, CLOCK);

    executor.execute(List.of(call("workspace:read"))).toCompletableFuture().join();

    assertThat(executions).hasValue(1);
    assertThat(ledger.find("call-1"))
        .get()
        .extracting(ToolExecutionRecord::status)
        .isEqualTo(ToolExecutionRecord.Status.COMPLETED);
  }

  @Test
  void asksBeforeRetryingAnUncertainExternalEffect() {
    InMemoryLedger ledger = new InMemoryLedger();
    ledger.save(record("shell:run", ToolExecutionRecord.Status.PENDING));
    AtomicInteger executions = new AtomicInteger();
    ToolExecutor delegate =
        calls -> {
          executions.incrementAndGet();
          return CompletableFuture.completedFuture(List.of(completed(calls.getFirst())));
        };
    LedgeredToolExecutor executor = new LedgeredToolExecutor(delegate, ledger, CLOCK);

    ToolResult result =
        executor.execute(List.of(call("shell:run"))).toCompletableFuture().join().getFirst();

    assertThat(executions).hasValue(0);
    assertThat(result.status()).isEqualTo(ToolResult.Status.APPROVAL_REQUIRED);
    assertThat(result.metadata()).containsKey("approvalRequest");
    assertThat(ledger.find("call-1"))
        .get()
        .extracting(ToolExecutionRecord::status)
        .isEqualTo(ToolExecutionRecord.Status.UNKNOWN);
  }

  @Test
  void resumesARegularApprovalWithoutTreatingItAsAnUncertainSideEffect() {
    InMemoryLedger ledger = new InMemoryLedger();
    ToolCall call = call("workspace:edit");
    ApprovalRequest approval =
        new ApprovalRequest(
            UUID.randomUUID(),
            call,
            RiskLevel.MEDIUM,
            "App.java",
            "apply diff",
            Optional.of("before"),
            Optional.of("diff"),
            CLOCK.instant());
    AtomicInteger executions = new AtomicInteger();
    ToolExecutor delegate =
        new ToolExecutor() {
          @Override
          public java.util.concurrent.CompletionStage<List<ToolResult>> execute(
              List<ToolCall> calls) {
            return execute(calls, Optional.empty(), Optional.empty());
          }

          @Override
          public java.util.concurrent.CompletionStage<List<ToolResult>> execute(
              List<ToolCall> calls,
              Optional<ApprovalRequest> pending,
              Optional<ApprovalDecision> decision) {
            executions.incrementAndGet();
            if (decision.isEmpty()) {
              return CompletableFuture.completedFuture(
                  List.of(
                      new ToolResult(
                          call.toolCallId(),
                          ToolResult.Status.APPROVAL_REQUIRED,
                          "approval",
                          Optional.empty(),
                          Map.of("approvalRequest", approval))));
            }
            return CompletableFuture.completedFuture(List.of(completed(call)));
          }
        };
    LedgeredToolExecutor executor = new LedgeredToolExecutor(delegate, ledger, CLOCK);

    executor.execute(List.of(call)).toCompletableFuture().join();

    assertThat(ledger.find(call.toolCallId()))
        .get()
        .extracting(ToolExecutionRecord::status)
        .isEqualTo(ToolExecutionRecord.Status.AWAITING_APPROVAL);
    ApprovalDecision decision =
        new ApprovalDecision(
            approval.approvalId(),
            ApprovalDecision.Choice.ALLOW,
            ApprovalDecision.Scope.ONCE,
            Optional.empty(),
            CLOCK.instant());
    ToolResult result =
        executor
            .execute(List.of(call), Optional.of(approval), Optional.of(decision))
            .toCompletableFuture()
            .join()
            .getFirst();

    assertThat(result.status()).isEqualTo(ToolResult.Status.COMPLETED);
    assertThat(executions).hasValue(2);
  }

  private static ToolCall call(String name) {
    return new ToolCall("call-1", name, "{}");
  }

  private static ToolResult completed(ToolCall call) {
    return new ToolResult(
        call.toolCallId(),
        ToolResult.Status.COMPLETED,
        "done",
        Optional.of("results/call-1.txt"),
        Map.of());
  }

  private static ToolExecutionRecord record(String name, ToolExecutionRecord.Status status) {
    return new ToolExecutionRecord(
        "call-1",
        name,
        status,
        name.startsWith("workspace:") ? RiskLevel.LOW : RiskLevel.HIGH,
        Optional.empty(),
        Optional.empty(),
        status == ToolExecutionRecord.Status.COMPLETED
            ? Optional.of("results/call-1.txt")
            : Optional.empty(),
        CLOCK.instant());
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
