package dev.miniclaudecode.tools.process;

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
import dev.miniclaudecode.tools.fs.WorkspacePathResolver;
import dev.miniclaudecode.tools.process.ShellSelector.Platform;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void runsAReadOnlyCommandInTheWorkspaceWithoutApproval() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    RunCommandTool tool = this.tool(workspace);
    ToolResult result =
        (ToolResult)
            tool.execute(call("pwd", "."), context(workspace, Map.of()))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary()).contains(new CharSequence[] {workspace.toString()});
    Assertions.assertThat(result.metadata()).containsEntry("exitCode", 0);
  }

  @Test
  void pausesBeforeACommandWithSideEffectsAndRunsOnlyAfterMatchingApproval() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    ShellSelector selector = ShellSelector.system();
    String command =
        selector.platform() == Platform.WINDOWS
            ? "Set-Content -Path marker.txt -Value changed"
            : "printf changed > marker.txt";
    RunCommandTool tool = this.tool(workspace);
    ToolCall call = call(command, ".");
    ToolResult preview =
        (ToolResult) tool.execute(call, context(workspace, Map.of())).toCompletableFuture().get();
    Assertions.assertThat(preview.status()).isEqualTo(Status.APPROVAL_REQUIRED);
    Assertions.assertThat(workspace.resolve("marker.txt")).doesNotExist();
    ApprovalRequest request = (ApprovalRequest) preview.metadata().get("approvalRequest");
    ApprovalDecision decision =
        new ApprovalDecision(
            request.approvalId(),
            Choice.ALLOW,
            Scope.ONCE,
            Optional.empty(),
            Instant.parse("2026-07-21T00:00:00Z"));
    Map<String, Object> attributes =
        Map.of("approvalRequest", request, "approvalDecision", decision);
    ToolResult executed =
        (ToolResult) tool.execute(call, context(workspace, attributes)).toCompletableFuture().get();
    Assertions.assertThat(executed.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(Files.readString(workspace.resolve("marker.txt")))
        .contains(new CharSequence[] {"changed"});
  }

  @Test
  void rejectsAWorkingDirectoryOutsideTheWorkspace() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Files.createDirectory(this.temporaryDirectory.resolve("outside"));
    RunCommandTool tool = this.tool(workspace);
    ToolResult result =
        (ToolResult)
            tool.execute(call("pwd", "../outside"), context(workspace, Map.of()))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(result.summary()).containsIgnoringCase("outside");
  }

  @Test
  void requiredSandboxRefusalFailsTheOneCallInsteadOfTheWholeTurn() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    CommandSandbox refusing =
        CommandSandbox.detect(CommandSandbox.Policy.REQUIRED, workspace, "Windows 11", null);
    RunCommandTool tool =
        new RunCommandTool(
            new WorkspacePathResolver(workspace),
            new ProcessRunner(ShellSelector.system(), refusing),
            new ToolResultStore(
                Files.createDirectories(this.temporaryDirectory.resolve("results-refused"))));
    // The refusal is thrown on the async execution path; it must come back as a per-call FAILED
    // result, not an exceptionally completed future that the graph escalates to a failed turn.
    ToolResult result =
        (ToolResult)
            tool.execute(call("pwd", "."), context(workspace, Map.of()))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(result.summary()).contains("required");
  }

  @Test
  void classifiesDestructiveCommandsAsHighOrCriticalRisk() {
    CommandRiskClassifier classifier = new CommandRiskClassifier();
    Assertions.assertThat(classifier.classify("git reset --hard HEAD").name()).isEqualTo("HIGH");
    Assertions.assertThat(classifier.classify("rm -rf /").name()).isEqualTo("CRITICAL");
    Assertions.assertThat(classifier.classify("git status").name()).isEqualTo("LOW");
  }

  private RunCommandTool tool(Path workspace) throws Exception {
    return new RunCommandTool(
        new WorkspacePathResolver(workspace),
        new ProcessRunner(ShellSelector.system()),
        new ToolResultStore(Files.createDirectories(this.temporaryDirectory.resolve("results"))));
  }

  private static ToolCall call(String command, String workingDirectory) {
    return new ToolCall(
        "call-1",
        "shell:run",
        "{\"command\":\""
            + jsonEscape(command)
            + "\",\"workingDirectory\":\""
            + jsonEscape(workingDirectory)
            + "\",\"timeoutSeconds\":5}");
  }

  private static ToolContext context(Path workspace, Map<String, Object> attributes) {
    return new ToolContext(
        new SessionId("session-1"), new TurnId(1L), workspace, EventSink.NOOP, attributes);
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
