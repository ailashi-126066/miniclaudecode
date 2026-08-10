package dev.miniclaudecode.extensions.mcp;

import dev.miniclaudecode.domain.approval.RiskLevel;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record McpServerConfig(
    String name,
    McpServerConfig.Transport transport,
    List<String> command,
    URI url,
    Map<String, String> environment,
    Map<String, String> headers,
    Duration initializationTimeout,
    Duration operationTimeout,
    RiskLevel toolRisk) {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

  public McpServerConfig(
      String name,
      McpServerConfig.Transport transport,
      List<String> command,
      URI url,
      Map<String, String> environment,
      Map<String, String> headers,
      Duration initializationTimeout,
      Duration operationTimeout,
      RiskLevel toolRisk) {
    if (name != null && NAME.matcher(name).matches()) {
      Objects.requireNonNull(transport, "transport must not be null");
      command = List.copyOf(Objects.requireNonNull(command, "command must not be null"));
      environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
      headers = Map.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
      requirePositive(initializationTimeout, "initializationTimeout");
      requirePositive(operationTimeout, "operationTimeout");
      Objects.requireNonNull(toolRisk, "toolRisk must not be null");
      if (transport == McpServerConfig.Transport.STDIO && command.isEmpty()) {
        throw new IllegalArgumentException("stdio MCP server requires a command");
      } else if (command.stream().anyMatch(value -> value == null || value.isBlank())) {
        throw new IllegalArgumentException("MCP command entries must not be blank");
      } else if (transport != McpServerConfig.Transport.STREAMABLE_HTTP
          || url != null
              && ("http".equalsIgnoreCase(url.getScheme())
                  || "https".equalsIgnoreCase(url.getScheme()))) {
        this.name = name;
        this.transport = transport;
        this.command = command;
        this.url = url;
        this.environment = environment;
        this.headers = headers;
        this.initializationTimeout = initializationTimeout;
        this.operationTimeout = operationTimeout;
        this.toolRisk = toolRisk;
      } else {
        throw new IllegalArgumentException("Streamable HTTP MCP server requires an http(s) URL");
      }
    } else {
      throw new IllegalArgumentException("MCP server name must match " + NAME.pattern());
    }
  }

  public static McpServerConfig stdio(String name, List<String> command) {
    return new McpServerConfig(
        name,
        McpServerConfig.Transport.STDIO,
        command,
        null,
        Map.of(),
        Map.of(),
        Duration.ofSeconds(20L),
        Duration.ofSeconds(60L),
        RiskLevel.HIGH);
  }

  public static McpServerConfig streamableHttp(String name, URI url) {
    return new McpServerConfig(
        name,
        McpServerConfig.Transport.STREAMABLE_HTTP,
        List.of(),
        url,
        Map.of(),
        Map.of(),
        Duration.ofSeconds(20L),
        Duration.ofSeconds(60L),
        RiskLevel.HIGH);
  }

  public String namespace() {
    return "mcp." + this.name;
  }

  private static void requirePositive(Duration value, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  public static enum Transport {
    STDIO,
    STREAMABLE_HTTP;
  }
}
