package dev.miniclaudecode.tools.approval;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.PermissionRule;
import dev.miniclaudecode.domain.approval.PermissionRuleStore;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.tools.approval.PermissionEngine.Authorization.Allowed;
import dev.miniclaudecode.tools.approval.PermissionEngine.Authorization.Requested;
import dev.miniclaudecode.tools.approval.PermissionEngine.MutationPlan;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class PermissionEngineTest {
  private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

  @Test
  void requestsDiffBoundApprovalThenAcceptsMatchingAllowOnce() {
    PermissionEngine engine = new PermissionEngine(PermissionRuleStore.NONE, clock());
    MutationPlan plan = plan("before", "diff");
    Requested requested = (Requested) engine.authorize(plan, context(Map.of()));
    ApprovalRequest request = requested.request();
    ApprovalDecision decision =
        new ApprovalDecision(request.approvalId(), Choice.ALLOW, Scope.ONCE, Optional.empty(), NOW);
    Assertions.assertThat(request.beforeHash()).contains("before");
    Assertions.assertThat(request.diffHash()).contains("diff");
    Assertions.assertThat(
            engine.authorize(
                plan, context(Map.of("approvalRequest", request, "approvalDecision", decision))))
        .isInstanceOf(Allowed.class);
  }

  @Test
  void invalidatesApprovalWhenSourceOrDiffChanges() {
    PermissionEngine engine = new PermissionEngine(PermissionRuleStore.NONE, clock());
    MutationPlan original = plan("before", "diff");
    ApprovalRequest request = ((Requested) engine.authorize(original, context(Map.of()))).request();
    ApprovalDecision decision =
        new ApprovalDecision(request.approvalId(), Choice.ALLOW, Scope.ONCE, Optional.empty(), NOW);
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () ->
                        engine.authorize(
                            plan("changed", "new-diff"),
                            context(
                                Map.of("approvalRequest", request, "approvalDecision", decision))))
                .isInstanceOf(SecurityException.class))
        .hasMessageContaining("changed");
  }

  @Test
  void permanentApprovalStoresOnlyExactWorkspaceToolAndTargetRule() {
    PermissionEngineTest.InMemoryRuleStore rules = new PermissionEngineTest.InMemoryRuleStore();
    PermissionEngine engine = new PermissionEngine(rules, clock());
    MutationPlan plan = plan("before", "diff");
    ApprovalRequest request = ((Requested) engine.authorize(plan, context(Map.of()))).request();
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(), Choice.ALLOW, Scope.PERMANENT, Optional.empty(), NOW);
    engine.authorize(
        plan, context(Map.of("approvalRequest", request, "approvalDecision", decision)));
    Assertions.assertThat(rules.list())
        .singleElement()
        .satisfies(
            rule -> {
              Assertions.assertThat(rule.workspace()).isEqualTo("C:/workspace");
              Assertions.assertThat(rule.qualifiedToolName()).isEqualTo("workspace:edit");
              Assertions.assertThat(rule.normalizedTarget()).isEqualTo("src/App.java");
            });
    Assertions.assertThat(engine.authorize(plan, context(Map.of()))).isInstanceOf(Allowed.class);
    Assertions.assertThat(
            engine.authorize(
                plan("other-before", "other-diff", "src/Other.java"), context(Map.of())))
        .isInstanceOf(Requested.class);
  }

  @Test
  void turnApprovalDoesNotLeakIntoAnotherSessionWithTheSameTurnNumber() {
    PermissionEngine engine = new PermissionEngine(PermissionRuleStore.NONE, clock());
    MutationPlan plan = plan("before", "diff");
    ToolContext firstSession = context("session-1", Map.of());
    ApprovalRequest request = ((Requested) engine.authorize(plan, firstSession)).request();
    ApprovalDecision decision =
        new ApprovalDecision(request.approvalId(), Choice.ALLOW, Scope.TURN, Optional.empty(), NOW);

    Assertions.assertThat(
            engine.authorize(
                plan,
                context(
                    "session-1", Map.of("approvalRequest", request, "approvalDecision", decision))))
        .isInstanceOf(Allowed.class);
    Assertions.assertThat(engine.authorize(plan, context("session-1", Map.of())))
        .isInstanceOf(Allowed.class);
    Assertions.assertThat(engine.authorize(plan, context("session-2", Map.of())))
        .isInstanceOf(Requested.class);
  }

  private static MutationPlan plan(String beforeHash, String diffHash) {
    return plan(beforeHash, diffHash, "src/App.java");
  }

  private static MutationPlan plan(String beforeHash, String diffHash, String target) {
    return new MutationPlan(
        new ToolCall("call-1", "workspace:edit", "{\"path\":\"src/App.java\"}"),
        RiskLevel.MEDIUM,
        "C:/workspace",
        target,
        "edit file",
        beforeHash,
        diffHash,
        "--- a/src/App.java\n+++ b/src/App.java\n@@ -1 +1 @@\n-old\n+new\n");
  }

  private static ToolContext context(Map<String, Object> attributes) {
    return context("session-1", attributes);
  }

  private static ToolContext context(String sessionId, Map<String, Object> attributes) {
    return new ToolContext(
        new SessionId(sessionId), new TurnId(1L), Path.of("."), EventSink.NOOP, attributes);
  }

  private static Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static final class InMemoryRuleStore implements PermissionRuleStore {
    private final List<PermissionRule> rules = new ArrayList<>();

    public List<PermissionRule> list() {
      return List.copyOf(this.rules);
    }

    public void save(PermissionRule rule) {
      this.rules.add(rule);
    }
  }
}
