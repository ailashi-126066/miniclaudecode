package dev.miniclaudecode.extensions.mcp;

import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpResourceToolsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void listsAndReadsResourcesThroughPermissionAwareAgentTools() throws Exception {
    FakeMcpClient client =
        new FakeMcpClient(
                "docs",
                List.of(),
                request -> ToolExecutionResult.builder().resultText("unused").build())
            .resources(
                List.of(new McpResource("file:///guide", "guide", "Project guide", "text/plain")),
                new McpReadResourceResult(
                    List.of(
                        new McpTextResourceContents(
                            "file:///guide", "MCP resource body", "text/plain"))));
    List<AgentTool> tools =
        McpResourceTools.create(
            "mcp.docs",
            client,
            RiskLevel.HIGH,
            new ToolResultStore(Files.createDirectory(this.temporaryDirectory.resolve("results"))),
            1024);
    ToolResult listed = this.execute(tools.getFirst(), "list", "{}");
    ToolResult read = this.execute(tools.getLast(), "read", "{\"uri\":\"file:///guide\"}");
    Assertions.assertThat(tools)
        .allSatisfy(
            tool -> Assertions.assertThat(tool.descriptor().baseRisk()).isEqualTo(RiskLevel.HIGH));
    Assertions.assertThat(listed.summary())
        .contains(new CharSequence[] {"file:///guide", "Project guide"});
    Assertions.assertThat(read.summary()).isEqualTo("MCP resource body");
  }

  private ToolResult execute(AgentTool tool, String id, String arguments) throws Exception {
    return (ToolResult)
        tool.execute(
                new ToolCall(id, tool.descriptor().qualifiedName(), arguments),
                new ToolContext(
                    new SessionId("session-1"),
                    new TurnId(1L),
                    this.temporaryDirectory,
                    EventSink.NOOP,
                    Map.of()))
            .toCompletableFuture()
            .get();
  }
}
