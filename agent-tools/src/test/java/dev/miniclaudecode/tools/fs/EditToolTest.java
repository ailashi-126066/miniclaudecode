package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Choice;
import dev.miniclaudecode.domain.approval.ApprovalDecision.Scope;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.approval.PermissionEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditToolTest {
  @TempDir Path tempDirectory;
  private Path workspace;
  private Path file;
  private EditTool tool;

  @BeforeEach
  void setUp() throws Exception {
    this.workspace = Files.createDirectory(this.tempDirectory.resolve("workspace"));
    this.file = Files.writeString(this.workspace.resolve("App.java"), "class App {\n}\n");
    this.tool = new EditTool(new WorkspacePathResolver(this.workspace), new PermissionEngine());
  }

  @Test
  void previewsDiffWithoutWritingThenAppliesMatchingApproval() throws Exception {
    ToolCall call = editCall("class App", "public class App");
    ToolResult preview =
        (ToolResult) this.tool.execute(call, this.context(Map.of())).toCompletableFuture().get();
    Assertions.assertThat(preview.status()).isEqualTo(Status.APPROVAL_REQUIRED);
    ((AbstractStringAssert)
            Assertions.assertThat(preview.summary()).contains(new CharSequence[] {"-class App {"}))
        .contains(new CharSequence[] {"+public class App {"});
    Assertions.assertThat(Files.readString(this.file)).isEqualTo("class App {\n}\n");
    ApprovalRequest request = (ApprovalRequest) preview.metadata().get("approvalRequest");
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(), Choice.ALLOW, Scope.ONCE, Optional.empty(), Instant.now());
    ToolResult applied =
        (ToolResult)
            this.tool
                .execute(
                    call,
                    this.context(Map.of("approvalRequest", request, "approvalDecision", decision)))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(applied.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(Files.readString(this.file)).isEqualTo("public class App {\n}\n");
    Assertions.assertThat(applied.metadata())
        .containsKeys(new String[] {"beforeHash", "afterHash", "diffHash"});
  }

  @Test
  void concurrentSourceChangeInvalidatesOldApproval() throws Exception {
    ToolCall call = editCall("class App", "public class App");
    ToolResult preview =
        (ToolResult) this.tool.execute(call, this.context(Map.of())).toCompletableFuture().get();
    ApprovalRequest request = (ApprovalRequest) preview.metadata().get("approvalRequest");
    Files.writeString(this.file, "class App {\n  int changed;\n}\n");
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(), Choice.ALLOW, Scope.ONCE, Optional.empty(), Instant.now());
    ToolResult result =
        (ToolResult)
            this.tool
                .execute(
                    call,
                    this.context(Map.of("approvalRequest", request, "approvalDecision", decision)))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(result.summary()).contains(new CharSequence[] {"changed"});
    ((AbstractStringAssert)
            Assertions.assertThat(Files.readString(this.file))
                .contains(new CharSequence[] {"int changed"}))
        .doesNotContain(new CharSequence[] {"public class"});
  }

  @Test
  void sensitivePathIsElevatedToHighRisk() throws Exception {
    Path env = Files.writeString(this.workspace.resolve(".env"), "TOKEN=old\n");
    ToolCall call =
        new ToolCall(
            "call-env",
            "workspace:edit",
            "{\"path\":\".env\",\"oldText\":\"old\",\"newText\":\"new\"}");
    ToolResult preview =
        (ToolResult) this.tool.execute(call, this.context(Map.of())).toCompletableFuture().get();
    ApprovalRequest request = (ApprovalRequest) preview.metadata().get("approvalRequest");
    Assertions.assertThat(request.riskLevel()).isEqualTo(RiskLevel.HIGH);
    Assertions.assertThat(Files.readString(env)).isEqualTo("TOKEN=old\n");
  }

  private ToolContext context(Map<String, Object> attributes) {
    return new ToolContext(
        new SessionId("session-1"), new TurnId(1L), this.workspace, EventSink.NOOP, attributes);
  }

  private static ToolCall editCall(String oldText, String newText) {
    return new ToolCall(
        "call-1",
        "workspace:edit",
        "{\"path\":\"App.java\",\"oldText\":\"" + oldText + "\",\"newText\":\"" + newText + "\"}");
  }
}
