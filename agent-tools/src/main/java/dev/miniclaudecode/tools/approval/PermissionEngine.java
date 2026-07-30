package dev.miniclaudecode.tools.approval;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.PermissionRule;
import dev.miniclaudecode.domain.approval.PermissionRuleStore;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PermissionEngine {
  public static final String APPROVAL_REQUEST_ATTRIBUTE = "approvalRequest";
  public static final String APPROVAL_DECISION_ATTRIBUTE = "approvalDecision";
  private final PermissionRuleStore ruleStore;
  private final Clock clock;

  /**
   * In-memory allowances granted with {@link Scope#FILE} and {@link Scope#TURN}.
   *
   * <p>Both scopes are offered by the approval menu but used to be silently discarded: only {@link
   * Scope#PERMANENT} was ever consulted, so "allow for this turn" and "allow for this file" behaved
   * exactly like "allow once" while the documentation advertised four working scopes. They are held
   * here rather than in the {@link PermissionRuleStore} precisely because they must not outlive the
   * process — persisting them would silently widen a temporary grant into a permanent one.
   */
  private final Set<String> fileAllowances = ConcurrentHashMap.newKeySet();

  private final Set<String> turnAllowances = ConcurrentHashMap.newKeySet();

  public PermissionEngine() {
    this(PermissionRuleStore.NONE, Clock.systemUTC());
  }

  public PermissionEngine(PermissionRuleStore ruleStore, Clock clock) {
    this.ruleStore = Objects.requireNonNull(ruleStore, "ruleStore must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public PermissionEngine.Authorization authorize(
      PermissionEngine.MutationPlan plan, ToolContext context) {
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(context, "context must not be null");
    if (Boolean.TRUE.equals(context.attributes().get("isolatedWorktree"))) {
      return new PermissionEngine.Authorization.Allowed();
    }
    if (this.ruleStore.list().stream()
            .anyMatch(
                rule -> rule.matches(plan.workspace(), plan.call().qualifiedName(), plan.target()))
        || this.fileAllowances.contains(fileKey(plan))
        || this.turnAllowances.contains(turnKey(plan, context))) {
      return new PermissionEngine.Authorization.Allowed();
    } else {
      Object requestValue = context.attributes().get("approvalRequest");
      Object decisionValue = context.attributes().get("approvalDecision");
      if (requestValue == null && decisionValue == null) {
        return new PermissionEngine.Authorization.Requested(this.newRequest(plan));
      } else {
        if (requestValue instanceof ApprovalRequest request
            && decisionValue instanceof ApprovalDecision decision) {
          validateBinding(plan, request, decision);
          if (decision.choice() == Choice.REJECT) {
            return new PermissionEngine.Authorization.Rejected(
                decision.feedback().orElse("user rejected the proposed file change"));
          }

          switch (decision.scope()) {
            case PERMANENT ->
                this.ruleStore.save(
                    new PermissionRule(
                        UUID.randomUUID(),
                        plan.workspace(),
                        plan.call().qualifiedName(),
                        plan.target(),
                        Instant.now(this.clock)));
            // "this file": any later mutation of the same file in this process is pre-approved.
            case FILE -> this.fileAllowances.add(fileKey(plan));
            // "this turn": the same tool on the same target until the turn id changes.
            case TURN -> this.turnAllowances.add(turnKey(plan, context));
            case ONCE -> {
              // No allowance is retained; the next call asks again.
            }
          }

          return new PermissionEngine.Authorization.Allowed();
        }

        throw new IllegalArgumentException(
            "approval request and decision must be supplied together");
      }
    }
  }

  /** A FILE allowance covers one file in one workspace, for any file-mutation tool. */
  private static String fileKey(PermissionEngine.MutationPlan plan) {
    return plan.workspace() + "\u0000" + plan.target();
  }

  /** A TURN allowance additionally pins the tool and the turn it was granted in. */
  private static String turnKey(PermissionEngine.MutationPlan plan, ToolContext context) {
    return plan.workspace()
        + "\u0000"
        + context.sessionId().value()
        + "\u0000"
        + context.turnId().value()
        + "\u0000"
        + plan.call().qualifiedName()
        + "\u0000"
        + plan.target();
  }

  private ApprovalRequest newRequest(PermissionEngine.MutationPlan plan) {
    return new ApprovalRequest(
        UUID.randomUUID(),
        plan.call(),
        plan.riskLevel(),
        plan.target(),
        plan.reason(),
        Optional.of(plan.beforeHash()),
        Optional.of(plan.diffHash()),
        Instant.now(this.clock));
  }

  private static void validateBinding(
      PermissionEngine.MutationPlan plan, ApprovalRequest request, ApprovalDecision decision) {
    if (!request.approvalId().equals(decision.approvalId())) {
      throw new SecurityException("approval decision does not match the request");
    } else {
      boolean matches =
          request.toolCall().equals(plan.call())
              && request.target().equals(plan.target())
              && request.beforeHash().equals(Optional.of(plan.beforeHash()))
              && request.diffHash().equals(Optional.of(plan.diffHash()));
      if (!matches) {
        throw new SecurityException("file or diff changed after approval was requested");
      }
    }
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  public sealed interface Authorization
      permits PermissionEngine.Authorization.Requested,
          PermissionEngine.Authorization.Allowed,
          PermissionEngine.Authorization.Rejected {
    public static record Allowed() implements PermissionEngine.Authorization {}

    public static record Rejected(String feedback) implements PermissionEngine.Authorization {
      public Rejected(String feedback) {
        feedback = PermissionEngine.requireText(feedback, "feedback");
        this.feedback = feedback;
      }
    }

    public static record Requested(ApprovalRequest request)
        implements PermissionEngine.Authorization {}
  }

  public static record MutationPlan(
      ToolCall call,
      RiskLevel riskLevel,
      String workspace,
      String target,
      String reason,
      String beforeHash,
      String diffHash,
      String unifiedDiff) {
    public MutationPlan(
        ToolCall call,
        RiskLevel riskLevel,
        String workspace,
        String target,
        String reason,
        String beforeHash,
        String diffHash,
        String unifiedDiff) {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(riskLevel, "riskLevel must not be null");
      workspace = PermissionEngine.requireText(workspace, "workspace");
      target = PermissionEngine.requireText(target, "target");
      reason = PermissionEngine.requireText(reason, "reason");
      beforeHash = PermissionEngine.requireText(beforeHash, "beforeHash");
      diffHash = PermissionEngine.requireText(diffHash, "diffHash");
      unifiedDiff = PermissionEngine.requireText(unifiedDiff, "unifiedDiff");
      this.call = call;
      this.riskLevel = riskLevel;
      this.workspace = workspace;
      this.target = target;
      this.reason = reason;
      this.beforeHash = beforeHash;
      this.diffHash = diffHash;
      this.unifiedDiff = unifiedDiff;
    }
  }
}
