package dev.miniclaudecode.extensions.mcp;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpPromptArgument;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class McpPromptCatalog {
  private final String server;
  private final McpClient client;

  public McpPromptCatalog(String server, McpClient client) {
    this.server = Objects.requireNonNull(server, "server must not be null");
    this.client = Objects.requireNonNull(client, "client must not be null");
  }

  public List<McpPromptCatalog.PromptDescriptor> list() {
    return this.client.listPrompts().stream().map(this::descriptor).toList();
  }

  public McpPromptCatalog.PromptValue get(String name, Map<String, Object> arguments) {
    McpGetPromptResult result = this.client.getPrompt(name, Map.copyOf(arguments));
    return new McpPromptCatalog.PromptValue(
        this.server,
        name,
        Objects.requireNonNullElse(result.description(), ""),
        result.messages().stream().map(message -> message.toChatMessage().toString()).toList());
  }

  private McpPromptCatalog.PromptDescriptor descriptor(McpPrompt prompt) {
    return new McpPromptCatalog.PromptDescriptor(
        this.server,
        prompt.name(),
        Objects.requireNonNullElse(prompt.description(), ""),
        prompt.arguments().stream().map(McpPromptCatalog::argument).toList());
  }

  private static McpPromptCatalog.Argument argument(McpPromptArgument value) {
    return new McpPromptCatalog.Argument(value.name(), value.description(), value.required());
  }

  public static record Argument(String name, String description, boolean required) {}

  public static record PromptDescriptor(
      String server, String name, String description, List<McpPromptCatalog.Argument> arguments) {
    public PromptDescriptor(
        String server, String name, String description, List<McpPromptCatalog.Argument> arguments) {
      arguments = List.copyOf(arguments);
      this.server = server;
      this.name = name;
      this.description = description;
      this.arguments = arguments;
    }
  }

  public static record PromptValue(
      String server, String name, String description, List<String> messages) {
    public PromptValue(String server, String name, String description, List<String> messages) {
      messages = List.copyOf(messages);
      this.server = server;
      this.name = name;
      this.description = description;
      this.messages = messages;
    }
  }
}
