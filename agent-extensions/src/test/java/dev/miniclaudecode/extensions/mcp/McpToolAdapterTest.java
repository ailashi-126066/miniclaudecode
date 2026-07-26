package dev.miniclaudecode.extensions.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolAdapterTest {
  @TempDir Path temporaryDirectory;

  @Test
  void preservesSchemaNamespaceRiskAndRoutesExecutionThroughLocalToolContract() throws Exception {
    AtomicReference<String> arguments = new AtomicReference<>();
    ToolSpecification specification = specification("search");
    FakeMcpClient client =
        new FakeMcpClient(
            "github",
            List.of(specification),
            request -> {
              arguments.set(request.arguments());
              return ToolExecutionResult.builder().resultText("found issue 42").build();
            });
    McpToolAdapter adapter =
        new McpToolAdapter(
            "mcp.github",
            client,
            specification,
            RiskLevel.HIGH,
            new ToolResultStore(Files.createDirectory(this.temporaryDirectory.resolve("results"))),
            1024);
    ToolResult result =
        (ToolResult)
            adapter
                .execute(
                    new ToolCall("call-1", "mcp.github:search", "{\"query\":\"bug\"}"),
                    this.context())
                .toCompletableFuture()
                .get();
    Assertions.assertThat(adapter.descriptor().qualifiedName()).isEqualTo("mcp.github:search");
    Assertions.assertThat(adapter.descriptor().inputSchemaJson())
        .contains(new CharSequence[] {"query", "required"});
    Assertions.assertThat(adapter.descriptor().baseRisk()).isEqualTo(RiskLevel.HIGH);
    Assertions.assertThat(arguments.get()).isEqualTo("{\"query\":\"bug\"}");
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary()).isEqualTo("found issue 42");
  }

  @Test
  void storesLargeMcpResultsByContentReference() throws Exception {
    ToolSpecification specification = specification("download");
    FakeMcpClient client =
        new FakeMcpClient(
            "files",
            List.of(specification),
            request ->
                ToolExecutionResult.builder().resultText("large-result-".repeat(100)).build());
    ToolResultStore store =
        new ToolResultStore(
            Files.createDirectory(this.temporaryDirectory.resolve("large-results")));
    McpToolAdapter adapter =
        new McpToolAdapter("mcp.files", client, specification, RiskLevel.HIGH, store, 80);
    ToolResult result =
        (ToolResult)
            adapter
                .execute(new ToolCall("call-2", "mcp.files:download", "{}"), this.context())
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.resultReference()).isPresent();
    Assertions.assertThat(store.read((String) result.resultReference().orElseThrow()))
        .startsWith("large-result-");
  }

  static ToolSpecification specification(String name) {
    return ToolSpecification.builder()
        .name(name)
        .description("test MCP tool")
        .parameters(
            JsonObjectSchema.builder()
                .addStringProperty("query")
                .required(new String[] {"query"})
                .build())
        .build();
  }

  private ToolContext context() {
    return new ToolContext(
        new SessionId("session-1"),
        new TurnId(1L),
        this.temporaryDirectory,
        EventSink.NOOP,
        Map.of());
  }
}
