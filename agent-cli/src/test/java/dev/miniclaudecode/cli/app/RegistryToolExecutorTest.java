package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.registry.DefaultToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryToolExecutorTest {
  @TempDir Path workspace;

  @Test
  void requiresAHostApprovalBeforeInvokingAnMcpTool() {
    final AtomicInteger invocations = new AtomicInteger();
    List<AgentEvent> events = new ArrayList<>();
    AgentTool mcp =
        new AgentTool() {
          public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                "mcp.demo", "publish", "publish", "{\"type\":\"object\"}", RiskLevel.HIGH);
          }

          public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(
                new ToolResult(
                    call.toolCallId(), Status.COMPLETED, "published", Optional.empty(), Map.of()));
          }
        };
    RegistryToolExecutor executor = this.executor(mcp, events::add);
    ToolCall call = new ToolCall("mcp-1", "mcp.demo:publish", "{}");
    ToolResult waiting = executor.execute(List.of(call)).toCompletableFuture().join().getFirst();
    Assertions.assertThat(waiting.status()).isEqualTo(Status.APPROVAL_REQUIRED);
    Assertions.assertThat(invocations).hasValue(0);
    ApprovalRequest request = (ApprovalRequest) waiting.metadata().get("approvalRequest");
    Assertions.assertThat(events)
        .filteredOn(event -> event.type() == AgentEventType.APPROVAL_REQUESTED)
        .singleElement()
        .satisfies(
            event ->
                Assertions.assertThat(event.payload())
                    .containsEntry("approvalId", request.approvalId().toString())
                    .containsEntry("toolCallId", "mcp-1")
                    .containsEntry("tool", "mcp.demo:publish")
                    .containsEntry("arguments", "{}")
                    .containsEntry("risk", "HIGH")
                    .containsEntry("target", "mcp.demo:publish"));
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(),
            Choice.ALLOW,
            Scope.ONCE,
            Optional.empty(),
            Instant.parse("2026-07-21T00:00:00Z"));
    ToolResult completed =
        executor
            .execute(List.of(call), Optional.of(request), Optional.of(decision))
            .toCompletableFuture()
            .join()
            .getFirst();
    Assertions.assertThat(completed.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(invocations).hasValue(1);
  }

  private RegistryToolExecutor executor(AgentTool tool) {
    return this.executor(tool, EventSink.NOOP);
  }

  private RegistryToolExecutor executor(AgentTool tool, EventSink audit) {
    return new RegistryToolExecutor(
        new DefaultToolRegistry(List.of(tool)),
        SessionId.of("session"),
        TurnId.of(1L),
        this.workspace,
        audit,
        new CancellationToken(),
        new ArrayList()::add,
        Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));
  }
}
