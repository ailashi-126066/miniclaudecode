package dev.miniclaudecode.tools.user;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AskUserToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void pausesAndReturnsFeedbackAsStructuredUserAnswer() throws Exception {
    AskUserTool tool =
        new AskUserTool(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    ToolCall call = new ToolCall("call-1", "user:ask", "{\"question\":\"Which module?\"}");
    ToolResult requested =
        (ToolResult) tool.execute(call, this.context(Map.of())).toCompletableFuture().get();
    ApprovalRequest request = (ApprovalRequest) requested.metadata().get("approvalRequest");
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(),
            Choice.ALLOW,
            Scope.ONCE,
            Optional.of("agent-runtime"),
            Instant.parse("2026-01-01T00:00:01Z"));
    ToolResult answered =
        (ToolResult)
            tool.execute(
                    call,
                    this.context(Map.of("approvalRequest", request, "approvalDecision", decision)))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(requested.status()).isEqualTo(Status.APPROVAL_REQUIRED);
    Assertions.assertThat(answered.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(answered.summary()).isEqualTo("agent-runtime");
  }

  private ToolContext context(Map<String, Object> attributes) {
    return new ToolContext(
        new SessionId("session-1"),
        new TurnId(1L),
        this.temporaryDirectory,
        EventSink.NOOP,
        attributes);
  }
}
