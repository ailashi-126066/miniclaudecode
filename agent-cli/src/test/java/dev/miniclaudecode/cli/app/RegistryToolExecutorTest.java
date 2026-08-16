package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.PermissionRule;
import dev.miniclaudecode.domain.approval.PermissionRuleStore;
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
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.runtime.PlanExecutionContext;
import dev.miniclaudecode.tools.registry.DefaultToolRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    Optional<PlanExecutionContext> plan =
        Optional.of(
            new PlanExecutionContext(
                UUID.randomUUID(), "step-1", Set.of(ToolEffect.EXTERNAL_EFFECT)));
    ToolResult waiting =
        executor
            .execute(List.of(call), Optional.empty(), Optional.empty(), plan)
            .toCompletableFuture()
            .join()
            .getFirst();
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
            .execute(List.of(call), Optional.of(request), Optional.of(decision), plan)
            .toCompletableFuture()
            .join()
            .getFirst();
    Assertions.assertThat(completed.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(invocations).hasValue(1);
  }

  @Test
  void rejectsASideEffectWithoutAnActivePlanStep() {
    AgentTool mutation =
        new AgentTool() {
          public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                "workspace",
                "write",
                "write",
                "{\"type\":\"object\"}",
                RiskLevel.MEDIUM,
                ToolEffect.MUTATION);
          }

          public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
            throw new AssertionError("plan gate should reject before invocation");
          }
        };
    ToolResult result =
        executor(mutation)
            .execute(List.of(new ToolCall("write-1", "workspace:write", "{}")))
            .toCompletableFuture()
            .join()
            .getFirst();

    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(result.metadata()).containsEntry("planGate", "denied");
  }

  @Test
  void mcpTurnAndPermanentScopesStopTheRepeatedPromptingPerCall() {
    final AtomicInteger invocations = new AtomicInteger();
    InMemoryRuleStore rules = new InMemoryRuleStore();
    RegistryToolExecutor executor = this.executor(mcpTool(invocations), rules);
    ToolCall call = new ToolCall("mcp-1", "mcp.demo:search", "{\"q\":\"first\"}");
    Optional<PlanExecutionContext> plan =
        Optional.of(
            new PlanExecutionContext(
                UUID.randomUUID(), "step-1", Set.of(ToolEffect.EXTERNAL_EFFECT)));

    ApprovalRequest request =
        (ApprovalRequest)
            run(executor, call, Optional.empty(), Optional.empty(), plan)
                .metadata()
                .get("approvalRequest");
    ApprovalDecision turn = decision(request, Scope.TURN);
    Assertions.assertThat(
            run(executor, call, Optional.of(request), Optional.of(turn), plan).status())
        .isEqualTo(Status.COMPLETED);

    // A different query on the same approved tool must not prompt again: the rule is keyed by tool,
    // not by argument JSON, which is what made "always allow" meaningless before.
    ToolCall second = new ToolCall("mcp-2", "mcp.demo:search", "{\"q\":\"second\"}");
    Assertions.assertThat(run(executor, second, Optional.empty(), Optional.empty(), plan).status())
        .isEqualTo(Status.COMPLETED);
    Assertions.assertThat(invocations).hasValue(2);

    // A fresh turn drops the in-memory allowance; PERMANENT is what survives, on disk.
    RegistryToolExecutor laterTurn = this.executor(mcpTool(invocations), rules);
    ApprovalRequest reprompt =
        (ApprovalRequest)
            run(laterTurn, call, Optional.empty(), Optional.empty(), plan)
                .metadata()
                .get("approvalRequest");
    Assertions.assertThat(reprompt).isNotNull();
    run(
        laterTurn,
        call,
        Optional.of(reprompt),
        Optional.of(decision(reprompt, Scope.PERMANENT)),
        plan);
    Assertions.assertThat(rules.list()).hasSize(1);

    RegistryToolExecutor afterPermanent = this.executor(mcpTool(invocations), rules);
    Assertions.assertThat(
            run(afterPermanent, call, Optional.empty(), Optional.empty(), plan).status())
        .isEqualTo(Status.COMPLETED);
  }

  private static ToolResult run(
      RegistryToolExecutor executor,
      ToolCall call,
      Optional<ApprovalRequest> request,
      Optional<ApprovalDecision> decision,
      Optional<PlanExecutionContext> plan) {
    return executor
        .execute(List.of(call), request, decision, plan)
        .toCompletableFuture()
        .join()
        .getFirst();
  }

  private static ApprovalDecision decision(ApprovalRequest request, Scope scope) {
    return new ApprovalDecision(
        request.approvalId(),
        Choice.ALLOW,
        scope,
        Optional.empty(),
        Instant.parse("2026-07-21T00:00:00Z"));
  }

  private static AgentTool mcpTool(AtomicInteger invocations) {
    return new AgentTool() {
      public ToolDescriptor descriptor() {
        return new ToolDescriptor(
            "mcp.demo", "search", "search", "{\"type\":\"object\"}", RiskLevel.HIGH);
      }

      public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
        invocations.incrementAndGet();
        return CompletableFuture.completedFuture(
            new ToolResult(
                call.toolCallId(), Status.COMPLETED, "found", Optional.empty(), Map.of()));
      }
    };
  }

  private RegistryToolExecutor executor(AgentTool tool) {
    return this.executor(tool, EventSink.NOOP);
  }

  private RegistryToolExecutor executor(AgentTool tool, EventSink audit) {
    return this.executor(tool, audit, PermissionRuleStore.NONE);
  }

  private RegistryToolExecutor executor(AgentTool tool, PermissionRuleStore rules) {
    return this.executor(tool, EventSink.NOOP, rules);
  }

  private RegistryToolExecutor executor(
      AgentTool tool, EventSink audit, PermissionRuleStore rules) {
    return new RegistryToolExecutor(
        new DefaultToolRegistry(List.of(tool)),
        SessionId.of("session"),
        TurnId.of(1L),
        this.workspace,
        audit,
        new CancellationToken(),
        new ArrayList()::add,
        Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
        Map.of(),
        rules);
  }

  private static final class InMemoryRuleStore implements PermissionRuleStore {
    private final List<PermissionRule> rules = new ArrayList<>();

    @Override
    public List<PermissionRule> list() {
      return List.copyOf(this.rules);
    }

    @Override
    public void save(PermissionRule rule) {
      this.rules.add(rule);
    }
  }
}
