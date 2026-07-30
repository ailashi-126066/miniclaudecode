package dev.miniclaudecode.tools.fs;

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
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confirms an isolated {@link WriteTool} bound to a worktree-scoped {@link WorkspacePathResolver}
 * refuses any escaping target at the executor level, independent of prompt or attributes.
 */
class WriteToolContainmentTest {
  @TempDir Path tempDirectory;
  private Path worktree;
  private WriteTool tool;

  @BeforeEach
  void setUp() throws Exception {
    this.worktree = Files.createDirectory(this.tempDirectory.resolve("worktree"));
    this.tool = new WriteTool(new WorkspacePathResolver(this.worktree), new PermissionEngine());
  }

  @Test
  void refusesAbsolutePathOutsideWorktree() throws Exception {
    Path outside = this.tempDirectory.resolve("escape.txt");
    ToolResult result = write(outside.toString().replace("\\", "\\\\"), "pwned");
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(result.summary()).containsIgnoringCase("relative");
    Assertions.assertThat(Files.exists(outside)).isFalse();
  }

  @Test
  void refusesParentTraversalOutsideWorktree() throws Exception {
    ToolResult result = write("../escape.txt", "pwned");
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(result.summary()).containsIgnoringCase("outside");
    Assertions.assertThat(Files.exists(this.tempDirectory.resolve("escape.txt"))).isFalse();
  }

  @Test
  void refusesWriteThroughSymlinkEscapingWorktree() throws Exception {
    Path outsideDir = Files.createDirectory(this.tempDirectory.resolve("outside"));
    Path link = this.worktree.resolve("linked");
    try {
      Files.createSymbolicLink(link, outsideDir);
    } catch (UnsupportedOperationException | SecurityException | java.io.IOException error) {
      Assumptions.assumeTrue(false, "symbolic links are not available: " + error.getMessage());
    }

    ToolResult result = write("linked/escape.txt", "pwned");
    Assertions.assertThat(result.status()).isEqualTo(Status.FAILED);
    Assertions.assertThat(Files.exists(outsideDir.resolve("escape.txt"))).isFalse();
  }

  private ToolResult write(String path, String content) throws Exception {
    ToolCall call =
        new ToolCall(
            "call-1",
            "workspace:write",
            "{\"path\":\"" + path + "\",\"content\":\"" + content + "\"}");
    return (ToolResult) this.tool.execute(call, context()).toCompletableFuture().get();
  }

  private ToolContext context() {
    return new ToolContext(
        new SessionId("session-1"), new TurnId(1L), this.worktree, EventSink.NOOP, Map.of());
  }
}
