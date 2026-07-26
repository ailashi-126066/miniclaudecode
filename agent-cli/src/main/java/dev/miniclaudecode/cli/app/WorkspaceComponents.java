package dev.miniclaudecode.cli.app;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.extensions.mcp.McpManager;
import dev.miniclaudecode.extensions.mcp.McpManager.ConnectReport;
import dev.miniclaudecode.extensions.skill.LoadSkillTool;
import dev.miniclaudecode.extensions.skill.SkillCatalog;
import dev.miniclaudecode.persistence.config.AppConfig;
import dev.miniclaudecode.persistence.config.ConfigLoader;
import dev.miniclaudecode.persistence.config.EmbeddingConfig;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import dev.miniclaudecode.persistence.permission.JsonPermissionRuleStore;
import dev.miniclaudecode.providers.ProviderFactory;
import dev.miniclaudecode.rag.embedding.LocalCodeEmbeddingModel;
import dev.miniclaudecode.rag.embedding.RemoteEmbeddingModel;
import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import dev.miniclaudecode.rag.search.Bm25Retriever;
import dev.miniclaudecode.rag.search.HybridCodeSearcher;
import dev.miniclaudecode.rag.search.VectorRetriever;
import dev.miniclaudecode.rag.tool.CodeSearchTool;
import dev.miniclaudecode.tools.approval.PermissionEngine;
import dev.miniclaudecode.tools.fs.ApplyPatchTool;
import dev.miniclaudecode.tools.fs.EditTool;
import dev.miniclaudecode.tools.fs.GlobTool;
import dev.miniclaudecode.tools.fs.GrepTool;
import dev.miniclaudecode.tools.fs.ListTool;
import dev.miniclaudecode.tools.fs.ReadTool;
import dev.miniclaudecode.tools.fs.WorkspacePathResolver;
import dev.miniclaudecode.tools.fs.WriteTool;
import dev.miniclaudecode.tools.process.CommandSandbox;
import dev.miniclaudecode.tools.process.ProcessRunner;
import dev.miniclaudecode.tools.process.RunCommandTool;
import dev.miniclaudecode.tools.process.ShellSelector;
import dev.miniclaudecode.tools.registry.DefaultToolRegistry;
import dev.miniclaudecode.tools.result.ToolResultStore;
import dev.miniclaudecode.tools.task.TodoTool;
import dev.miniclaudecode.tools.user.AskUserTool;
import dev.miniclaudecode.tools.web.WebFetchTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
  private final McpManager mcpManager;
  private final ConnectReport mcpReport;
  private final TodoTool todoTool;

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
      McpManager mcpManager,
      ConnectReport mcpReport,
      TodoTool todoTool) {
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
    this.mcpManager = mcpManager;
    this.mcpReport = mcpReport;
    this.todoTool = todoTool;
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
    } else {
      Optional<Path> projectConfig =
          Optional.of(workspace.resolve(".mini-claude-code/config.yaml"));
      AppConfig config = new ConfigLoader().load(layout.configFile(), projectConfig);
      ModelClient modelClient =
          fakeResponse
              .<ModelClient>map(StaticResponseModelClient::new)
              .orElseGet(
                  () ->
                      new RoutingModelClient(
                          config.providers(), environment, new ProviderFactory()));
      ToolResultStore results =
          new ToolResultStore(layout.toolResultsRoot().resolve(layout.workspaceHash(workspace)));
      WorkspacePathResolver paths = new WorkspacePathResolver(workspace);
      JsonPermissionRuleStore permissionRules =
          new JsonPermissionRuleStore(layout.permissionsFile());
      PermissionEngine permissions = new PermissionEngine(permissionRules, Clock.systemUTC());
      EmbeddingModel embeddings = embeddingModel(config.embedding(), environment);
      LuceneCodeIndex codeIndex =
          new LuceneCodeIndex(layout.indexWorkspaceRoot(workspace), embeddings);
      Bm25Retriever bm25 = new Bm25Retriever(codeIndex.luceneDirectory());
      VectorRetriever vector = new VectorRetriever(codeIndex.luceneDirectory(), embeddings);
      HybridCodeSearcher searcher = new HybridCodeSearcher(bm25, vector);
      SkillCatalog skills = SkillCatalog.discover(workspace, layout.skillsRoot());
      WorkspaceComponents.McpWiring mcp = wireMcp(layout, results);

      try {
        List<AgentTool> agentTools = new ArrayList<>();
        agentTools.add(new ReadTool(paths, results));
        agentTools.add(new ListTool(paths, results));
        agentTools.add(new GlobTool(paths, results));
        agentTools.add(new GrepTool(paths, results));
        agentTools.add(new WriteTool(paths, permissions));
        agentTools.add(new EditTool(paths, permissions));
        agentTools.add(new ApplyPatchTool(paths, permissions));
        CommandSandbox sandbox =
            CommandSandbox.detect(
                CommandSandbox.Policy.parse(environment.getOrDefault("MINICLAUDE_SANDBOX", "auto")),
                workspace);
        agentTools.add(
            new RunCommandTool(paths, new ProcessRunner(ShellSelector.system(), sandbox), results));
        agentTools.add(new WebFetchTool(results));
        TodoTool todoTool = new TodoTool();
        agentTools.add(todoTool);
        agentTools.add(new AskUserTool());
        agentTools.add(new CodeSearchTool(codeIndex, searcher));
        agentTools.add(new LoadSkillTool(skills));
        agentTools.addAll(mcp.tools());
        Set<String> secrets =
            config.providers().values().stream()
                .map(profile -> profile.resolvedApiKey(environment))
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
        return new WorkspaceComponents(
            workspace,
            layout,
            config,
            new DefaultToolRegistry(agentTools),
            modelClient,
            skills,
            codeIndex,
            bm25,
            vector,
            searcher,
            secrets,
            mcp.manager(),
            mcp.report(),
            todoTool);
      } catch (RuntimeException failure) {
        // Everything below wireMcp runs after connectAll has already spawned stdio child
        // processes. Nothing owns the manager until the WorkspaceComponents instance exists, so a
        // failure here (a duplicate tool name in the registry, a failing tool constructor) would
        // otherwise leave those children running with nobody left to close them.
        mcp.manager().close();
        throw failure;
      }
    }
  }

  /**
   * Selects the embedding provider from configuration. {@code fast} keeps the offline hashing model
   * (zero dependencies, reproducible); {@code remote} points the index at an OpenAI-compatible
   * embeddings endpoint. The index itself persists the provider identity and rebuilds when it
   * changes, so switching here can never silently mix vector spaces.
   */
  private static EmbeddingModel embeddingModel(
      EmbeddingConfig embedding, Map<String, String> environment) {
    return switch (embedding.provider()) {
      case FAST -> new LocalCodeEmbeddingModel(embedding.dimensions());
      case REMOTE ->
          new RemoteEmbeddingModel(
              embedding
                  .baseUrl()
                  .orElseThrow(
                      () ->
                          new IllegalStateException("remote embedding provider requires base-url")),
              embedding.resolvedApiKey(environment),
              embedding.model(),
              embedding.dimensions(),
              embedding.timeout());
    };
  }

  /**
   * Wires MCP so that no MCP problem can stop the agent from starting. Anything thrown while
   * loading the configuration, connecting, or adapting tools leaves the CLI running with zero MCP
   * tools, and the partially built manager is closed first so a spawned stdio child process is
   * never orphaned. docs/mcp-and-skills.md promises exactly this isolation.
   */
  private static WorkspaceComponents.McpWiring wireMcp(
      UserDataLayout layout, ToolResultStore results) {
    McpManager manager = null;

    try {
      McpConfigurationLoader.LoadResult configured =
          new McpConfigurationLoader().loadWithDiagnostics(layout.configFile());
      Set<String> launchApproved =
          configured.servers().stream()
              .filter(McpConfigurationLoader.ConfiguredServer::launchApproved)
              .map(value -> value.config().name())
              .collect(Collectors.toUnmodifiableSet());
      manager = new McpManager(results, configValue -> launchApproved.contains(configValue.name()));
      ConnectReport connected =
          manager.connectAll(
              configured.servers().stream()
                  .map(McpConfigurationLoader.ConfiguredServer::config)
                  .toList());
      // Rejected configuration entries are reported through the same channel as connect failures,
      // so `/mcp` shows them instead of them vanishing silently.
      Map<String, String> failures = new LinkedHashMap<>(connected.failures());
      configured
          .rejected()
          .forEach(
              value ->
                  failures.putIfAbsent(value.name(), "invalid configuration: " + value.reason()));
      return new WorkspaceComponents.McpWiring(
          manager, new ConnectReport(connected.connected(), failures), manager.tools());
    } catch (Exception failure) {
      if (manager != null) {
        manager.close();
      }

      String reason =
          Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
      return new WorkspaceComponents.McpWiring(
          new McpManager(results, configValue -> false),
          new ConnectReport(List.of(), Map.of("mcp", "disabled: " + reason)),
          List.of());
    }
  }

  private static record McpWiring(
      McpManager manager, ConnectReport report, List<AgentTool> tools) {}

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

  TodoTool todoTool() {
    return this.todoTool;
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
