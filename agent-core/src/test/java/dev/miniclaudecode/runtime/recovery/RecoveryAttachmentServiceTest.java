package dev.miniclaudecode.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.ToolMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryAttachmentServiceTest {
  @TempDir Path workspace;

  @Test
  void capturesDurableStateAndMarksChangedReadFilesStale() throws Exception {
    byte[] original = "class App {}\n".getBytes(StandardCharsets.UTF_8);
    Files.write(workspace.resolve("App.java"), original);
    List<AgentMessage> messages =
        List.of(
            new UserMessage("Implement the application"),
            new ToolMessage("read-1", "workspace:read", "1: class App {}", false),
            new ToolMessage(
                "write-1", "workspace:write", "Applied approved change to App.java", false),
            new ToolMessage("verify-1", "shell:run", "mvn test passed", false),
            new ToolMessage("skill-1", "skill:load", "Loaded java-review v2", false));
    ModelRequest request =
        new ModelRequest(
            "test",
            "model",
            messages,
            List.of(),
            false,
            512,
            Map.of(
                "workspace", workspace.toString(),
                "workspaceStatus", "dirty: App.java",
                "backgroundAgents", List.of("bg-1 RUNNING"),
                "teamTasks", List.of("task-1 ASSIGNED")));
    ToolResult read =
        new ToolResult(
            "read-1",
            ToolResult.Status.COMPLETED,
            "1: class App {}",
            Optional.of("sha256:" + "a".repeat(64)),
            Map.of(
                "path",
                "App.java",
                "startLine",
                1,
                "endLine",
                1,
                "contentHash",
                "sha256:" + sha256(original),
                "hashedBytes",
                original.length));
    MiniClaudeState state =
        new MiniClaudeState(
            Map.of(
                MiniClaudeState.REQUEST, request,
                MiniClaudeState.MESSAGES, messages,
                MiniClaudeState.TOOL_RESULTS, List.of(read),
                MiniClaudeState.DISCOVERED_TOOLS, List.of("shell:run"),
                MiniClaudeState.PROVIDER_METADATA,
                    Map.of("inputTokens", 123L, "outputTokens", 45L)));
    RecoveryAttachmentService service =
        new RecoveryAttachmentService(
            Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

    RecoveryAttachment first = service.capture(state);

    assertThat(first.objective()).isEqualTo("Implement the application");
    assertThat(first.modifiedFiles()).contains("Applied approved change to App.java");
    assertThat(first.verifications()).contains("mvn test passed");
    assertThat(first.loadedSkills()).contains("Loaded java-review v2");
    assertThat(first.discoveredTools()).containsExactly("shell:run");
    assertThat(first.backgroundAgents()).containsExactly("bg-1 RUNNING");
    assertThat(first.teamTasks()).containsExactly("task-1 ASSIGNED");
    assertThat(first.toolResultReferences()).contains("sha256:" + "a".repeat(64));
    assertThat(first.providerUsage()).containsEntry("inputTokens", 123L);
    assertThat(first.readFiles())
        .singleElement()
        .satisfies(file -> assertThat(file.stale()).isFalse());
    assertThat(first.toPromptText(512)).hasSizeLessThanOrEqualTo(535);

    Files.writeString(workspace.resolve("App.java"), "class Changed {}\n");
    Map<String, Object> restored = new LinkedHashMap<>(state.data());
    restored.put(MiniClaudeState.RECOVERY_ATTACHMENT, first);
    restored.put(MiniClaudeState.TOOL_RESULTS, List.of());

    RecoveryAttachment second = service.capture(new MiniClaudeState(Map.copyOf(restored)));

    assertThat(second.boundaryId()).isNotEqualTo(first.boundaryId());
    assertThat(second.readFiles())
        .singleElement()
        .satisfies(file -> assertThat(file.stale()).isTrue());
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
