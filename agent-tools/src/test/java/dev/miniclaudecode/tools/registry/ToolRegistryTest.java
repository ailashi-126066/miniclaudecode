package dev.miniclaudecode.tools.registry;

import dev.miniclaudecode.domain.approval.RiskLevel;
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
