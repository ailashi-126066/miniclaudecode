package dev.miniclaudecode.tools.registry;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
  @Test
  void resolvesQualifiedAndUniqueShortNames() {
    AgentTool workspaceRead = tool("workspace", "read");
    AgentTool mcpSearch = tool("github", "search");
    DefaultToolRegistry registry = new DefaultToolRegistry(List.of(workspaceRead, mcpSearch));
    Assertions.assertThat(registry.require("workspace:read")).isSameAs(workspaceRead);
    Assertions.assertThat(registry.require("search")).isSameAs(mcpSearch);
    Assertions.assertThat(registry.descriptors())
        .extracting(ToolDescriptor::qualifiedName)
        .containsExactly(new String[] {"github:search", "workspace:read"});
  }

  @Test
  void rejectsDuplicateQualifiedNamesAndAmbiguousShortNames() {
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () ->
                        new DefaultToolRegistry(
                            List.of(tool("workspace", "read"), tool("workspace", "read"))))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("workspace:read");
    DefaultToolRegistry registry =
        new DefaultToolRegistry(List.of(tool("workspace", "read"), tool("mcp", "read")));
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> registry.require("read"))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("ambiguous");
  }

  @Test
  void searchesThenExposesDeferredSchemasOnlyForTheSelectingSession() {
    AgentTool eager = tool("workspace", "read");
    AgentTool deferred = tool("shell", "run");
    DeferredToolRegistry registry =
        new DeferredToolRegistry(List.of(eager), List.of(deferred), 4_096);
    SessionId first = SessionId.of("first-session");
    SessionId second = SessionId.of("second-session");

    Assertions.assertThat(registry.descriptors(first))
        .extracting(ToolDescriptor::qualifiedName)
        .containsExactly("system:tool_search", "workspace:read");
    Assertions.assertThatThrownBy(() -> registry.require(first, "shell:run"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool_search");

    ToolCall search =
        new ToolCall(
            "search-1",
            "system:tool_search",
            "{\"query\":\"shell command\",\"select\":[\"shell:run\"]}");
    ToolResult result =
        registry
            .require(first, "system:tool_search")
            .execute(
                search,
                new ToolContext(
                    first, TurnId.of(1), java.nio.file.Path.of("."), EventSink.NOOP, Map.of()))
            .toCompletableFuture()
            .join();

    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.metadata()).containsKey("discoveredToolDescriptors");
    Assertions.assertThat(registry.descriptors(first))
        .extracting(ToolDescriptor::qualifiedName)
        .contains("shell:run");
    Assertions.assertThat(registry.require(first, "shell:run").descriptor().qualifiedName())
        .isEqualTo("shell:run");
    Assertions.assertThat(registry.descriptors(second))
        .extracting(ToolDescriptor::qualifiedName)
        .doesNotContain("shell:run");
  }

  private static AgentTool tool(String namespace, String name) {
    return new AgentTool() {
      private final ToolDescriptor descriptor =
          new ToolDescriptor(namespace, name, "test tool", "{\"type\":\"object\"}", RiskLevel.LOW);

      public ToolDescriptor descriptor() {
        return this.descriptor;
      }

      public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
        return CompletableFuture.completedFuture(
            new ToolResult(call.toolCallId(), Status.COMPLETED, "ok", Optional.empty(), Map.of()));
      }
    };
  }
}
