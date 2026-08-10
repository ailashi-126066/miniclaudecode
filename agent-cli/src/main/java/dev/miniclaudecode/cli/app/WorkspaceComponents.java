package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.extensions.mcp.McpManager;
import dev.miniclaudecode.extensions.mcp.McpManager.ConnectReport;
import dev.miniclaudecode.extensions.skill.SkillCatalog;
import dev.miniclaudecode.persistence.config.AppConfig;
import dev.miniclaudecode.persistence.memory.MemoryStore;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import dev.miniclaudecode.rag.search.Bm25Retriever;
import dev.miniclaudecode.rag.search.HybridCodeSearcher;
import dev.miniclaudecode.rag.search.VectorRetriever;
import dev.miniclaudecode.tools.registry.DefaultToolRegistry;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class WorkspaceComponents implements AutoCloseable {
  private final Path workspace;
  private final UserDataLayout layout;
  private final AppConfig config;
  private final DefaultToolRegistry tools;
  private final ModelClient modelClient;
  private final SkillCatalog skills;
  private final LuceneCodeIndex codeIndex;
  private final Bm25Retriever bm25;
  private final VectorRetriever vector;
  private final HybridCodeSearcher searcher;
  private final Set<String> secrets;
  private final MemoryStore bullets;
  private final McpManager mcpManager;
  private final ConnectReport mcpReport;

  private WorkspaceComponents(
      Path workspace,
      UserDataLayout layout,
      AppConfig config,
      DefaultToolRegistry tools,
      ModelClient modelClient,
      SkillCatalog skills,
      LuceneCodeIndex codeIndex,
      Bm25Retriever bm25,
      VectorRetriever vector,
      HybridCodeSearcher searcher,
      Set<String> secrets,
      MemoryStore bullets,
      McpManager mcpManager,
      ConnectReport mcpReport) {
    this.workspace = workspace;
    this.layout = layout;
    this.config = config;
    this.tools = tools;
    this.modelClient = modelClient;
    this.skills = skills;
    this.codeIndex = codeIndex;
    this.bm25 = bm25;
    this.vector = vector;
    this.searcher = searcher;
    this.secrets = secrets;
    this.bullets = bullets;
    this.mcpManager = mcpManager;
    this.mcpReport = mcpReport;
  }

  static WorkspaceComponents create(
      Path requestedWorkspace,
      UserDataLayout layout,
      Map<String, String> environment,
      Optional<String> fakeResponse)
      throws IOException {
    Path workspace = requestedWorkspace.toRealPath();
    if (!Files.isDirectory(workspace)) {
      throw new IllegalArgumentException("workspace must be a directory: " + workspace);
    }
    ModelWiringFactory.Wiring model =
        ModelWiringFactory.create(workspace, layout, environment, fakeResponse);
    ToolResultStore results =
        new ToolResultStore(layout.toolResultsRoot().resolve(layout.workspaceHash(workspace)));
    RagWiringFactory.Wiring rag =
        RagWiringFactory.create(workspace, layout, model.config().embedding(), environment);
    ExtensionWiringFactory.Wiring extensions =
        ExtensionWiringFactory.create(workspace, layout, results);
    try {
      ToolWiringFactory.Wiring tools =
          ToolWiringFactory.create(
              workspace,
              layout,
              model.config(),
              environment,
              model.secrets(),
              model.modelClient(),
              results,
              rag,
              extensions);
      return new WorkspaceComponents(
          workspace,
          layout,
          model.config(),
          tools.tools(),
          model.modelClient(),
          extensions.skills(),
          rag.codeIndex(),
          rag.bm25(),
          rag.vector(),
          rag.searcher(),
          model.secrets(),
          tools.bullets(),
          extensions.manager(),
          extensions.report());
    } catch (RuntimeException failure) {
      extensions.close();
      throw failure;
    }
  }

  Path workspace() {
    return this.workspace;
  }

  UserDataLayout layout() {
    return this.layout;
  }

  AppConfig config() {
    return this.config;
  }

  DefaultToolRegistry tools() {
    return this.tools;
  }

  ModelClient modelClient() {
    return this.modelClient;
  }

  SkillCatalog skills() {
    return this.skills;
  }

  LuceneCodeIndex codeIndex() {
    return this.codeIndex;
  }

  HybridCodeSearcher searcher() {
    return this.searcher;
  }

  Bm25Retriever bm25() {
    return this.bm25;
  }

  VectorRetriever vector() {
    return this.vector;
  }

  Set<String> secrets() {
    return this.secrets;
  }

  MemoryStore bullets() {
    return this.bullets;
  }

  String mcpStatus() {
    if (this.mcpReport.connected().isEmpty() && this.mcpReport.failures().isEmpty()) {
      return "(none configured)";
    } else {
      StringBuilder status = new StringBuilder();
      this.mcpManager
          .statuses()
          .forEach(
              value -> {
                status
                    .append(value.name())
                    .append(" [")
                    .append(value.transport())
                    .append("] tools=")
                    .append(value.discoveredTools());
                if (!value.shadowedTools().isEmpty()) {
                  // Tools dropped because they collide with the built-in MCP resource tools; users
                  // would otherwise wonder why a tool the server advertises is not callable.
                  status.append(" shadowed=").append(String.join(",", value.shadowedTools()));
                }

                status.append('\n');
              });
      this.mcpReport
          .failures()
          .forEach(
              (name, failure) ->
                  status.append(name).append(" [failed] ").append(failure).append('\n'));
      return status.toString().stripTrailing();
    }
  }

  @Override
  public void close() {
    this.mcpManager.close();
  }

  Map<String, List<String>> providerModels() {
    Map<String, List<String>> values = new LinkedHashMap<>();
    this.config.providers().forEach((name, profile) -> values.put(name, List.of(profile.model())));
    return Map.copyOf(values);
  }
}
