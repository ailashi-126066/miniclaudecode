package dev.miniclaudecode.tools.user;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.internal.ToolArguments;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class AskUserTool implements AgentTool {
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "user",
          "ask",
          "Pause the graph and ask the user one focused question",
          "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"}},\"required\":[\"question\"]}",
          RiskLevel.LOW);
  private final Clock clock;

  public AskUserTool() {
    this(Clock.systemUTC());
  }

  public AskUserTool(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      String question = ToolArguments.parse(call.argumentsJson()).requiredText("question");
      Object requestValue = context.attributes().get("approvalRequest");
      Object decisionValue = context.attributes().get("approvalDecision");
      if (requestValue == null && decisionValue == null) {
        ApprovalRequest request =
            new ApprovalRequest(
                UUID.randomUUID(),
                call,
                RiskLevel.LOW,
                question,
                "The agent needs user input before it can continue",
                Optional.empty(),
                Optional.empty(),
                Instant.now(this.clock));
        return CompletableFuture.completedFuture(
            new ToolResult(
                call.toolCallId(),
                Status.APPROVAL_REQUIRED,
                question,
                Optional.empty(),
                Map.of("approvalRequest", request, "interaction", "question")));
      } else {
        if (!(requestValue instanceof ApprovalRequest request)
            || !(decisionValue instanceof ApprovalDecision decision)
            || !request.toolCall().equals(call)
            || !request.target().equals(question)
            || !request.approvalId().equals(decision.approvalId())) {
          throw new SecurityException("user answer does not match the pending question");
        }

        if (decision.choice() == Choice.REJECT) {
          return CompletableFuture.completedFuture(
              new ToolResult(
                  call.toolCallId(),
                  Status.CANCELLED,
                  decision.feedback().orElse("User declined to answer"),
                  Optional.empty(),
                  Map.of("interaction", "question")));
        } else {
          String answer = decision.feedback().orElse("User confirmed without additional text.");
          return CompletableFuture.completedFuture(
              new ToolResult(
                  call.toolCallId(),
                  Status.COMPLETED,
                  answer,
                  Optional.empty(),
                  Map.of("interaction", "answer")));
        }
      }
    } catch (RuntimeException var9) {
      String message =
          var9.getMessage() == null ? var9.getClass().getSimpleName() : var9.getMessage();
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              Status.FAILED,
              "ask user failed: " + message,
              Optional.empty(),
              Map.of()));
    }
  }
}
