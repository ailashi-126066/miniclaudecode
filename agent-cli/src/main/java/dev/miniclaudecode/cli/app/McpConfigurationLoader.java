package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.extensions.mcp.McpServerConfig;
import dev.miniclaudecode.extensions.mcp.McpServerConfig.Transport;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class McpConfigurationLoader {
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  List<McpConfigurationLoader.ConfiguredServer> load(Path userConfig) throws IOException {
    return this.loadWithDiagnostics(userConfig).servers();
  }

  McpConfigurationLoader.LoadResult loadWithDiagnostics(Path userConfig) throws IOException {
    if (!Files.isRegularFile(userConfig)) {
      return McpConfigurationLoader.LoadResult.empty();
    } else {
      JsonNode root = this.yaml.readTree(userConfig.toFile());
      JsonNode servers = root.path("mcp").path("servers");
      if (servers.isMissingNode() || servers.isNull()) {
        return McpConfigurationLoader.LoadResult.empty();
      } else if (!servers.isObject()) {
        throw new IllegalArgumentException("mcp.servers must be an object");
      } else {
        List<McpConfigurationLoader.ConfiguredServer> result = new ArrayList<>();
        List<McpConfigurationLoader.RejectedServer> rejected = new ArrayList<>();
        // parse() used to throw straight out of this forEach, so a single bad entry (a typo in
        // transport, an unknown risk level) stopped the whole CLI from starting. MCP is optional
        // and per-server isolated, so a malformed entry is now skipped and surfaced as a
        // diagnostic; every other server, and the local agent, still start.
        servers
            .properties()
            .forEach(
                entry -> {
                  String name = (String) entry.getKey();

                  try {
                    result.add(parse(name, (JsonNode) entry.getValue()));
                  } catch (RuntimeException failure) {
                    rejected.add(
                        new McpConfigurationLoader.RejectedServer(name, describe(failure)));
                  }
                });
        return new McpConfigurationLoader.LoadResult(result, rejected);
      }
    }
  }

  private static McpConfigurationLoader.ConfiguredServer parse(String name, JsonNode node) {
    String transport = requiredText(node, "transport").toLowerCase(Locale.ROOT);
    Duration initialization =
        Duration.ofSeconds(node.path("initialization-timeout-seconds").asLong(20L));
    Duration operation = Duration.ofSeconds(node.path("operation-timeout-seconds").asLong(60L));
    RiskLevel risk = RiskLevel.valueOf(node.path("risk").asText("HIGH").toUpperCase(Locale.ROOT));
    Map<String, String> environment = stringMap(node.path("environment"));
    Map<String, String> headers = stringMap(node.path("headers"));
    boolean launchApproved = node.path("launch-approved").asBoolean(false);

    McpServerConfig config =
        switch (transport) {
          case "stdio" ->
              new McpServerConfig(
                  name,
                  Transport.STDIO,
                  stringList(node.path("command")),
                  null,
                  environment,
                  headers,
                  initialization,
                  operation,
                  risk);
          case "streamable-http", "http" ->
              new McpServerConfig(
                  name,
                  Transport.STREAMABLE_HTTP,
                  List.of(),
                  URI.create(requiredText(node, "url")),
                  environment,
                  headers,
                  initialization,
                  operation,
                  risk);
          default -> throw new IllegalArgumentException("unsupported MCP transport: " + transport);
        };
    return new McpConfigurationLoader.ConfiguredServer(config, launchApproved);
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isTextual() && !value.asText().isBlank()) {
      return value.asText().trim();
    } else {
      throw new IllegalArgumentException("MCP " + field + " must be non-blank text");
    }
  }

  private static List<String> stringList(JsonNode node) {
    if (!node.isArray()) {
      throw new IllegalArgumentException("MCP command must be an array");
    } else {
      List<String> values = new ArrayList<>();
      node.forEach(value -> values.add(value.asText()));
      return List.copyOf(values);
    }
  }

  private static Map<String, String> stringMap(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return Map.of();
    } else if (!node.isObject()) {
      throw new IllegalArgumentException("MCP environment and headers must be objects");
    } else {
      Map<String, String> values = new LinkedHashMap<>();
      node.properties()
          .forEach(
              entry -> values.put((String) entry.getKey(), ((JsonNode) entry.getValue()).asText()));
      return Map.copyOf(values);
    }
  }

  private static String describe(RuntimeException failure) {
    return Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
  }

  static record ConfiguredServer(McpServerConfig config, boolean launchApproved) {}

  /** One `mcp.servers` entry that could not be parsed, kept so the CLI can report it. */
  static record RejectedServer(String name, String reason) {}

  static record LoadResult(
      List<McpConfigurationLoader.ConfiguredServer> servers,
      List<McpConfigurationLoader.RejectedServer> rejected) {
    LoadResult(
        List<McpConfigurationLoader.ConfiguredServer> servers,
        List<McpConfigurationLoader.RejectedServer> rejected) {
      this.servers = List.copyOf(servers);
      this.rejected = List.copyOf(rejected);
    }

    static McpConfigurationLoader.LoadResult empty() {
      return new McpConfigurationLoader.LoadResult(List.of(), List.of());
    }
  }
}
