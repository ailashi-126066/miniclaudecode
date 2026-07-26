package dev.miniclaudecode.extensions.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceTemplate;
import dev.langchain4j.mcp.client.McpRoot;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class FakeMcpClient implements McpClient {
  private final String key;
  private final List<ToolSpecification> tools;
  private final Function<ToolExecutionRequest, ToolExecutionResult> executor;
  private List<McpResource> resources = List.of();
  private McpReadResourceResult resourceResult = new McpReadResourceResult(List.of());
  private List<McpPrompt> prompts = List.of();
  private boolean closed;

  FakeMcpClient(
      String key,
      List<ToolSpecification> tools,
      Function<ToolExecutionRequest, ToolExecutionResult> executor) {
    this.key = key;
    this.tools = List.copyOf(tools);
    this.executor = executor;
  }

  FakeMcpClient resources(List<McpResource> resources, McpReadResourceResult result) {
    this.resources = List.copyOf(resources);
    this.resourceResult = result;
    return this;
  }

  FakeMcpClient prompts(List<McpPrompt> prompts) {
    this.prompts = List.copyOf(prompts);
    return this;
  }

  boolean closed() {
    return this.closed;
  }

  public String key() {
    return this.key;
  }

  public List<ToolSpecification> listTools() {
    return this.tools;
  }

  public List<ToolSpecification> listTools(InvocationContext context) {
    return this.tools;
  }

  public ToolExecutionResult executeTool(ToolExecutionRequest request) {
    return this.executor.apply(request);
  }

  public ToolExecutionResult executeTool(ToolExecutionRequest request, InvocationContext context) {
    return this.executeTool(request);
  }

  public List<McpResource> listResources() {
    return this.resources;
  }

  public List<McpResource> listResources(InvocationContext context) {
    return this.resources;
  }

  public List<McpResourceTemplate> listResourceTemplates() {
    return List.of();
  }

  public List<McpResourceTemplate> listResourceTemplates(InvocationContext context) {
    return List.of();
  }

  public McpReadResourceResult readResource(String uri) {
    return this.resourceResult;
  }

  public McpReadResourceResult readResource(String uri, InvocationContext context) {
    return this.resourceResult;
  }

  public void subscribeToResource(String uri) {}

  public void unsubscribeFromResource(String uri) {}

  public List<McpPrompt> listPrompts() {
    return this.prompts;
  }

  public McpGetPromptResult getPrompt(String name, Map<String, Object> arguments) {
    return new McpGetPromptResult("prompt", List.of());
  }

  public void checkHealth() {}

  public void setRoots(List<McpRoot> roots) {}

  public void close() {
    this.closed = true;
  }
}
