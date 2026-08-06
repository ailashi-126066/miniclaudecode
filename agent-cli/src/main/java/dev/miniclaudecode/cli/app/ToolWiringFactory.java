package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.extensions.skill.LoadSkillTool;
import dev.miniclaudecode.extensions.skill.RouteSkillTool;
import dev.miniclaudecode.persistence.config.AppConfig;
import dev.miniclaudecode.persistence.memory.AceBulletStore;
import dev.miniclaudecode.persistence.memory.UserProfileStore;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import dev.miniclaudecode.persistence.permission.JsonPermissionRuleStore;
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
import dev.miniclaudecode.tools.process.CommandPolicy;
import dev.miniclaudecode.tools.process.CommandRiskClassifier;
import dev.miniclaudecode.tools.process.CommandSandbox;
import dev.miniclaudecode.tools.process.ProcessRunner;
import dev.miniclaudecode.tools.process.RunCommandTool;
import dev.miniclaudecode.tools.process.ShellSelector;
import dev.miniclaudecode.tools.registry.DefaultToolRegistry;
import dev.miniclaudecode.tools.result.ReadToolResultTool;
import dev.miniclaudecode.tools.result.ToolResultStore;
import dev.miniclaudecode.tools.task.TodoTool;
import dev.miniclaudecode.tools.user.AskUserTool;
import dev.miniclaudecode.tools.web.WebFetchTool;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the local tool registry and its workspace-scoped collaborators. */
final class ToolWiringFactory {
  private ToolWiringFactory() {}

  static Wiring create(
      Path workspace,
      UserDataLayout layout,
      AppConfig config,
      Map<String, String> environment,
      Set<String> secrets,
      ModelClient modelClient,
      ToolResultStore results,
      RagWiringFactory.Wiring rag,
      ExtensionWiringFactory.Wiring extensions) {
    WorkspacePathResolver paths = new WorkspacePathResolver(workspace);
    JsonPermissionRuleStore permissionRules = new JsonPermissionRuleStore(layout.permissionsFile());
    PermissionEngine permissions = new PermissionEngine(permissionRules, Clock.systemUTC());
    AceBulletStore bullets = new AceBulletStore(workspace);
    UserProfileStore profile = new UserProfileStore(layout.profileFile());
    List<AgentTool> tools = new ArrayList<>();
    tools.add(new ReadTool(paths, results));
    tools.add(new ListTool(paths, results));
    tools.add(new GlobTool(paths, results));
    tools.add(new GrepTool(paths, results));
    tools.add(new WriteTool(paths, permissions));
    tools.add(new EditTool(paths, permissions));
    tools.add(new ApplyPatchTool(paths, permissions));
    tools.add(commandTool(paths, workspace, config, environment, results, permissionRules));
    tools.add(new WebFetchTool(results));
    TodoTool todoTool = new TodoTool();
    tools.add(todoTool);
    tools.add(new AskUserTool());
    tools.add(new CodeSearchTool(rag.codeIndex(), rag.searcher(), results));
    tools.add(new ReadToolResultTool(results));
    tools.add(new MemorySearchTool(bullets));
    tools.add(new SessionSearchTool(workspace, layout, secrets));
    tools.add(new RouteSkillTool(extensions.skills()));
    tools.add(new LoadSkillTool(extensions.skills()));

    List<AgentTool> delegatedTools =
        tools.stream().filter(ToolWiringFactory::isDelegatedReadOnlyTool).toList();
    java.util.function.Function<Path, DefaultToolRegistry> isolatedTools =
        isolated ->
            isolatedRegistry(isolated, config, environment, results, permissions, permissionRules);
    IsolatedWorktreeService worktrees =
        new IsolatedWorktreeService(
            workspace, layout.sessionWorkspaceRoot(workspace).resolve("worktrees"));
    tools.add(new WorktreeControlTool(worktrees));
    tools.add(
        new DelegatedAgentTool(
            modelClient,
            new DefaultToolRegistry(delegatedTools),
            config.activeProvider(),
            config.activeProfile(),
            Clock.systemUTC(),
            isolatedTools,
            worktrees));
    tools.addAll(extensions.tools());
    return new Wiring(new DefaultToolRegistry(tools), bullets, profile, todoTool);
  }

  private static RunCommandTool commandTool(
      WorkspacePathResolver paths,
      Path workspace,
      AppConfig config,
      Map<String, String> environment,
      ToolResultStore results,
      JsonPermissionRuleStore permissionRules) {
    CommandSandbox sandbox =
        CommandSandbox.detect(
            CommandSandbox.Policy.parse(environment.getOrDefault("MINICLAUDE_SANDBOX", "auto")),
            workspace);
    return new RunCommandTool(
        paths,
        new ProcessRunner(ShellSelector.system(), sandbox),
        results,
        new CommandRiskClassifier(),
        new CommandPolicy(
            config.commandPolicy().allowPrefixes(),
            config.commandPolicy().denyFragments(),
            config.commandPolicy().allowlistOnly()),
        permissionRules,
        Clock.systemUTC());
  }

  private static DefaultToolRegistry isolatedRegistry(
      Path isolated,
      AppConfig config,
      Map<String, String> environment,
      ToolResultStore results,
      PermissionEngine permissions,
      JsonPermissionRuleStore permissionRules) {
    WorkspacePathResolver paths = new WorkspacePathResolver(isolated);
    return new DefaultToolRegistry(
        List.of(
            new ReadTool(paths, results),
            new ListTool(paths, results),
            new GlobTool(paths, results),
            new GrepTool(paths, results),
            new WriteTool(paths, permissions),
            new EditTool(paths, permissions),
            new ApplyPatchTool(paths, permissions),
            commandTool(paths, isolated, config, environment, results, permissionRules)));
  }

  private static boolean isDelegatedReadOnlyTool(AgentTool tool) {
    return Set.of(
            "workspace:read",
            "workspace:list",
            "workspace:glob",
            "workspace:grep",
            "workspace:code_search",
            "context:read_result",
            "memory:search",
            "session:search",
            "skills:route_skill",
            "skills:load_skill")
        .contains(tool.descriptor().qualifiedName());
  }

  record Wiring(
      DefaultToolRegistry tools,
      AceBulletStore bullets,
      UserProfileStore profile,
      TodoTool todoTool) {}
}
