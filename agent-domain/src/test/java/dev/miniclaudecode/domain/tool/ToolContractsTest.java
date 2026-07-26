package dev.miniclaudecode.domain.tool;

import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ToolContractsTest {
  @Test
  void buildsStableQualifiedToolName() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "builtin", "read", "Read a text file", "{\"type\":\"object\"}", RiskLevel.LOW);
    Assertions.assertThat(descriptor.qualifiedName()).isEqualTo("builtin:read");
  }

  @Test
  void toolCallKeepsImmutableJsonInput() {
    ToolCall call = new ToolCall("call-1", "builtin:edit", "{\"path\":\"pom.xml\",\"text\":\"x\"}");
    Assertions.assertThat(call.argumentsJson()).isEqualTo("{\"path\":\"pom.xml\",\"text\":\"x\"}");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> new ToolCall(" ", "builtin:read", "{}"))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("toolCallId");
  }

  @Test
  void fileApprovalBindsTargetAndBothHashes() {
    ToolCall call = new ToolCall("call-1", "builtin:edit", "{}");
    ApprovalRequest request =
        new ApprovalRequest(
            UUID.randomUUID(),
            call,
            RiskLevel.HIGH,
            "F:/workspace/pom.xml",
            "Apply the proposed diff",
            Optional.of("before-sha256"),
            Optional.of("diff-sha256"),
            Instant.parse("2026-07-20T12:00:00Z"));
    Assertions.assertThat(request.beforeHash()).contains("before-sha256");
    Assertions.assertThat(request.diffHash()).contains("diff-sha256");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () ->
                        new ApprovalRequest(
                            UUID.randomUUID(),
                            call,
                            RiskLevel.HIGH,
                            "F:/workspace/pom.xml",
                            "Apply the proposed diff",
                            Optional.of("before-sha256"),
                            Optional.empty(),
                            Instant.now()))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("together");
  }
}
