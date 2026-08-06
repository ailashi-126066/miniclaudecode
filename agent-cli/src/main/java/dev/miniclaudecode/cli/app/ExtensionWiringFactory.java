package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.extensions.mcp.McpManager;
import dev.miniclaudecode.extensions.mcp.McpManager.ConnectReport;
import dev.miniclaudecode.extensions.skill.SkillCatalog;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Discovers local skills and connects failure-isolated MCP servers. */
final class ExtensionWiringFactory {
  private ExtensionWiringFactory() {}

  static Wiring create(Path workspace, UserDataLayout layout, ToolResultStore results)
      throws IOException {
    SkillCatalog skills = SkillCatalog.discover(workspace, layout.skillsRoot());
    McpWiring mcp = wireMcp(layout, results);
    return new Wiring(skills, mcp.manager(), mcp.report(), mcp.tools());
  }

  private static McpWiring wireMcp(UserDataLayout layout, ToolResultStore results) {
    McpManager manager = null;
    try {
      McpConfigurationLoader.LoadResult configured =
          new McpConfigurationLoader().loadWithDiagnostics(layout.configFile());
      Set<String> launchApproved =
          configured.servers().stream()
              .filter(McpConfigurationLoader.ConfiguredServer::launchApproved)
              .map(value -> value.config().name())
              .collect(Collectors.toUnmodifiableSet());
      manager = new McpManager(results, config -> launchApproved.contains(config.name()));
      ConnectReport connected =
          manager.connectAll(
              configured.servers().stream()
                  .map(McpConfigurationLoader.ConfiguredServer::config)
                  .toList());
      Map<String, String> failures = new LinkedHashMap<>(connected.failures());
      configured
          .rejected()
          .forEach(
              value ->
                  failures.putIfAbsent(value.name(), "invalid configuration: " + value.reason()));
      return new McpWiring(
          manager, new ConnectReport(connected.connected(), failures), manager.tools());
    } catch (Exception failure) {
      if (manager != null) {
        manager.close();
      }
      String reason =
          java.util.Objects.requireNonNullElse(
              failure.getMessage(), failure.getClass().getSimpleName());
      McpManager disabled = new McpManager(results, config -> false);
      return new McpWiring(
          disabled, new ConnectReport(List.of(), Map.of("mcp", "disabled: " + reason)), List.of());
    }
  }

  record Wiring(
      SkillCatalog skills, McpManager manager, ConnectReport report, List<AgentTool> tools)
      implements AutoCloseable {
    @Override
    public void close() {
      manager.close();
    }
  }

  private record McpWiring(McpManager manager, ConnectReport report, List<AgentTool> tools) {}
}
