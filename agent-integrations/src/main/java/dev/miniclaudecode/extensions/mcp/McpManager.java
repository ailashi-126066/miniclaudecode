package dev.miniclaudecode.extensions.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class McpManager implements AutoCloseable {
  private final ToolResultStore resultStore;
  private final McpManager.LaunchAuthorizer launchAuthorizer;
  private final McpManager.ClientFactory clientFactory;
  private final int inlineByteLimit;
  private final Map<String, McpManager.Connection> connections = new LinkedHashMap<>();

  public McpManager(ToolResultStore resultStore, McpManager.LaunchAuthorizer launchAuthorizer) {
    this(resultStore, launchAuthorizer, McpManager::createClient, 32768);
  }

  public McpManager(
      ToolResultStore resultStore,
      McpManager.LaunchAuthorizer launchAuthorizer,
      McpManager.ClientFactory clientFactory,
      int inlineByteLimit) {
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    this.launchAuthorizer =
        Objects.requireNonNull(launchAuthorizer, "launchAuthorizer must not be null");
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
    if (inlineByteLimit < 1) {
      throw new IllegalArgumentException("inlineByteLimit must be positive");
    } else {
      this.inlineByteLimit = inlineByteLimit;
    }
  }

  public synchronized McpManager.Connection connect(McpServerConfig config) {
    Objects.requireNonNull(config, "config must not be null");
    if (this.connections.containsKey(config.name())) {
      throw new IllegalArgumentException("MCP server already connected: " + config.name());
    } else if (config.transport() == McpServerConfig.Transport.STDIO
        && !this.launchAuthorizer.approve(config)) {
      throw new SecurityException("MCP stdio launch was rejected: " + config.name());
    } else {
      McpClient client = this.clientFactory.create(config);

      try {
        McpManager.AdaptedTools adapted = this.adaptTools(config, client);
        McpPromptCatalog prompts = new McpPromptCatalog(config.name(), client);
        McpManager.Connection connection =
            new McpManager.Connection(
                config, client, adapted.tools(), adapted.shadowedTools(), prompts);
        this.connections.put(config.name(), connection);
        return connection;
      } catch (RuntimeException var6) {
        closeClient(client);
        throw var6;
      }
    }
  }

  public synchronized McpManager.ConnectReport connectAll(List<McpServerConfig> configurations) {
    Map<String, String> failures = new LinkedHashMap<>();
    List<String> connected = new ArrayList<>();

    for (McpServerConfig config : configurations) {
      try {
        this.connect(config);
        connected.add(config.name());
      } catch (RuntimeException var7) {
        failures.put(
            config.name(),
            Objects.requireNonNullElse(var7.getMessage(), var7.getClass().getSimpleName()));
      }
    }

    return new McpManager.ConnectReport(connected, failures);
  }

  public synchronized List<AgentTool> tools() {
    return this.connections.values().stream()
        .flatMap(connection -> connection.tools().stream())
        .toList();
  }

  public synchronized List<McpManager.ConnectionStatus> statuses() {
    return this.connections.values().stream()
        .map(
            connection ->
                new McpManager.ConnectionStatus(
                    connection.config().name(),
                    connection.config().transport(),
                    connection.tools().size(),
                    connection.shadowedTools()))
        .toList();
  }

  public synchronized void disconnect(String name) {
    McpManager.Connection connection = this.connections.remove(name);
    if (connection != null) {
      closeClient(connection.client());
    }
  }

  @Override
  public synchronized void close() {
    this.connections.values().forEach(connection -> closeClient(connection.client()));
    this.connections.clear();
  }

  private McpManager.AdaptedTools adaptTools(McpServerConfig config, McpClient client) {
    // The resource tools land in the same namespace as the server's own tools, so a server that
    // exposes "list_resources"/"read_resource" used to produce two identical qualified names and
    // DefaultToolRegistry aborted startup with "duplicate tool name". Renaming our resource tools
    // would change a documented, user-visible tool surface, so the resource tool names are reserved
    // instead (read off their descriptors rather than hard-coded, so they cannot drift) and the
    // colliding server tools are skipped and reported: a quirky server costs two of its tools, it
    // never stops the local agent from starting.
    List<AgentTool> resourceTools =
        McpResourceTools.create(
            config.namespace(), client, config.toolRisk(), this.resultStore, this.inlineByteLimit);
    Set<String> reserved = new LinkedHashSet<>();
    resourceTools.forEach(tool -> reserved.add(tool.descriptor().name()));
    List<AgentTool> tools = new ArrayList<>();
    List<String> shadowed = new ArrayList<>();
    Map<String, Boolean> names = new LinkedHashMap<>();

    for (ToolSpecification specification : client.listTools()) {
      if (reserved.contains(specification.name())) {
        shadowed.add(specification.name());
      } else {
        if (names.putIfAbsent(specification.name(), Boolean.TRUE) != null) {
          throw new IllegalArgumentException(
              "duplicate MCP tool in " + config.name() + ": " + specification.name());
        }

        tools.add(
            new McpToolAdapter(
                config.namespace(),
                client,
                specification,
                config.toolRisk(),
                this.resultStore,
                this.inlineByteLimit));
      }
    }

    tools.addAll(resourceTools);
    return new McpManager.AdaptedTools(tools, shadowed);
  }

  private static McpClient createClient(McpServerConfig config) {
    McpTransport transport =
        (McpTransport)
            (switch (config.transport()) {
              case STDIO ->
                  StdioMcpTransport.builder()
                      .command(config.command())
                      .environment(config.environment())
                      .build();
              case STREAMABLE_HTTP ->
                  StreamableHttpMcpTransport.builder()
                      .url(config.url().toString())
                      .customHeaders(config.headers())
                      .timeout(config.operationTimeout())
                      .build();
            });
    return DefaultMcpClient.builder()
        .key(config.name())
        .clientName("MiniClaudeCode")
        .clientVersion("0.1.0")
        .transport(transport)
        .initializationTimeout(config.initializationTimeout())
        .toolExecutionTimeout(config.operationTimeout())
        .resourcesTimeout(config.operationTimeout())
        .promptsTimeout(config.operationTimeout())
        .build();
  }

  private static void closeClient(McpClient client) {
    try {
      client.close();
    } catch (Exception var2) {
    }
  }

  @FunctionalInterface
  public interface ClientFactory {
    McpClient create(McpServerConfig config);
  }

  public static record ConnectReport(List<String> connected, Map<String, String> failures) {
    public ConnectReport(List<String> connected, Map<String, String> failures) {
      connected = List.copyOf(connected);
      failures = Map.copyOf(failures);
      this.connected = connected;
      this.failures = failures;
    }
  }

  public static final class Connection {
    private final McpServerConfig config;
    private final McpClient client;
    private final List<AgentTool> tools;
    private final List<String> shadowedTools;
    private final McpPromptCatalog prompts;

    private Connection(
        McpServerConfig config,
        McpClient client,
        List<AgentTool> tools,
        List<String> shadowedTools,
        McpPromptCatalog prompts) {
      this.config = config;
      this.client = client;
      this.tools = List.copyOf(tools);
      this.shadowedTools = List.copyOf(shadowedTools);
      this.prompts = prompts;
    }

    public McpServerConfig config() {
      return this.config;
    }

    public List<AgentTool> tools() {
      return this.tools;
    }

    /** Server tools dropped because their name is reserved by the built-in MCP resource tools. */
    public List<String> shadowedTools() {
      return this.shadowedTools;
    }

    public McpPromptCatalog prompts() {
      return this.prompts;
    }

    private McpClient client() {
      return this.client;
    }
  }

  public static record ConnectionStatus(
      String name,
      McpServerConfig.Transport transport,
      int discoveredTools,
      List<String> shadowedTools) {
    public ConnectionStatus(
        String name,
        McpServerConfig.Transport transport,
        int discoveredTools,
        List<String> shadowedTools) {
      this.name = name;
      this.transport = transport;
      this.discoveredTools = discoveredTools;
      this.shadowedTools = List.copyOf(shadowedTools);
    }

    // Kept so existing three-argument callers still compile; shadowing is the exceptional case.
    public ConnectionStatus(String name, McpServerConfig.Transport transport, int discoveredTools) {
      this(name, transport, discoveredTools, List.of());
    }
  }

  private static record AdaptedTools(List<AgentTool> tools, List<String> shadowedTools) {
    private AdaptedTools(List<AgentTool> tools, List<String> shadowedTools) {
      this.tools = List.copyOf(tools);
      this.shadowedTools = List.copyOf(shadowedTools);
    }
  }

  @FunctionalInterface
  public interface LaunchAuthorizer {
    boolean approve(McpServerConfig config);
  }
}
