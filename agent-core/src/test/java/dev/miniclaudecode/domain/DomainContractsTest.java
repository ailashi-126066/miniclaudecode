package dev.miniclaudecode.domain;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.PermissionRule;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class DomainContractsTest {
  private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

  @Test
  void messagesNormalizeAndSnapshotTheirInputs() {
    ToolCall call = new ToolCall("call-1", "workspace:read", "{}");
    List<ToolCall> calls = new ArrayList<>(List.of(call));
    Map<String, Object> metadata = new HashMap<>(Map.of("finish", "tool_calls"));
    AssistantMessage assistant =
        new AssistantMessage("answer", Optional.of("  reasoning  "), calls, metadata);
    calls.clear();
    metadata.clear();

    Assertions.assertThat(new SystemMessage("  system  ").text()).isEqualTo("system");
    Assertions.assertThat(new UserMessage("  user  ").text()).isEqualTo("user");
    Assertions.assertThat(new ToolMessage("id", "workspace:read", "", false).text()).isEmpty();
    Assertions.assertThat(assistant.thinking()).contains("reasoning");
    Assertions.assertThat(assistant.toolCalls()).containsExactly(call);
    Assertions.assertThat(assistant.providerMetadata()).containsEntry("finish", "tool_calls");
    Assertions.assertThatThrownBy(() -> new UserMessage(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void approvalsRulesAndResultsSurviveSerialization() throws Exception {
    ToolCall call = new ToolCall("call-1", "workspace:edit", "{}");
    ApprovalRequest request =
        new ApprovalRequest(
            UUID.randomUUID(),
            call,
            RiskLevel.MEDIUM,
            "src/App.java",
            "apply diff",
            Optional.of("before"),
            Optional.of("diff"),
            NOW);
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(),
            ApprovalDecision.Choice.ALLOW,
            ApprovalDecision.Scope.FILE,
            Optional.of("  reviewed  "),
            NOW);
    ToolResult result =
        new ToolResult(
            call.toolCallId(),
            ToolResult.Status.COMPLETED,
            "done",
            Optional.of("sha256:result"),
            Map.of("path", "src/App.java"));

    Assertions.assertThat(roundTrip(request)).isEqualTo(request);
    Assertions.assertThat(roundTrip(decision)).isEqualTo(decision);
    Assertions.assertThat(roundTrip(result)).isEqualTo(result);
    Assertions.assertThat(decision.feedback()).contains("reviewed");
    Assertions.assertThat(request.isBoundToDiff()).isTrue();
    Assertions.assertThat(result.isError()).isFalse();

    PermissionRule rule =
        new PermissionRule(UUID.randomUUID(), "workspace", "workspace:edit", "src/App.java", NOW);
    Assertions.assertThat(rule.matches("workspace", "workspace:edit", "src/App.java")).isTrue();
    Assertions.assertThat(rule.matches("workspace", "workspace:write", "src/App.java")).isFalse();
  }

  @Test
  void cancellationRunsEachLiveCallbackOnceAndContainsCallbackFailures() {
    CancellationToken token = new CancellationToken();
    AtomicInteger callbacks = new AtomicInteger();
    CancellationToken.Registration removed = token.onCancel(callbacks::incrementAndGet);
    removed.close();
    token.onCancel(callbacks::incrementAndGet);
    token.onCancel(
        () -> {
          throw new IllegalStateException("ignored callback failure");
        });

    Assertions.assertThat(token.cancel()).isTrue();
    Assertions.assertThat(token.cancel()).isFalse();
    Assertions.assertThat(token.isCancellationRequested()).isTrue();
    Assertions.assertThat(callbacks).hasValue(1);
    token.onCancel(callbacks::incrementAndGet);
    Assertions.assertThat(callbacks).hasValue(2);
  }

  @SuppressWarnings("unchecked")
  private static <T> T roundTrip(T value) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(value);
    }
    try (ObjectInputStream input =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      return (T) input.readObject();
    }
  }
}
