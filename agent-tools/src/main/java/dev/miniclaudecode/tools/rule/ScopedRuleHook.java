package dev.miniclaudecode.tools.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.tools.hook.AgentHook;
import dev.miniclaudecode.tools.hook.HookContext;
import dev.miniclaudecode.tools.hook.HookDecision;
import dev.miniclaudecode.tools.hook.HookPhase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Enforces project-owned, path-scoped deny rules from {@code .miniclaudecode/rules/*.md}.
 *
 * <p>A rule starts with a small front matter block, for example: {@code ---\npaths:
 * generated/**\naction: deny\n---\nGenerated files are never edited.}
 */
public final class ScopedRuleHook implements AgentHook {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path workspace;
  private final List<Rule> rules;

  private ScopedRuleHook(Path workspace, List<Rule> rules) {
    this.workspace = workspace;
    this.rules = List.copyOf(rules);
  }

  public static ScopedRuleHook load(Path workspace) {
    Path root = workspace.toAbsolutePath().normalize();
    List<Rule> loaded = new ArrayList<>();
    Path directory = root.resolve(".miniclaudecode/rules");
    if (Files.isDirectory(directory)) {
      try (var files = Files.walk(directory, 4)) {
        files
            .filter(path -> path.getFileName().toString().endsWith(".md"))
            .sorted()
            .forEach(path -> parse(path).ifPresent(loaded::add));
      } catch (IOException error) {
        throw new IllegalStateException("cannot load project rules", error);
      }
    }
    return new ScopedRuleHook(root, loaded);
  }

  @Override
  public HookDecision evaluate(HookContext context) {
    if (context.phase() != HookPhase.BEFORE_TOOL || !mutatesWorkspace(context.call())) {
      return HookDecision.allow();
    }
    for (String target : targets(context.call())) {
      Path absolute = workspace.resolve(target).normalize();
      if (!absolute.startsWith(workspace)) {
        return HookDecision.deny("rule target escapes the workspace: " + target);
      }
      Path relative = workspace.relativize(absolute);
      for (Rule rule : rules) {
        if (rule.matcher.matches(relative)) {
          return HookDecision.deny("project rule " + rule.source + ": " + rule.reason);
        }
      }
    }
    return HookDecision.allow();
  }

  private static boolean mutatesWorkspace(ToolCall call) {
    return switch (call.qualifiedName()) {
      case "workspace:write", "workspace:edit", "workspace:apply_patch" -> true;
      default -> false;
    };
  }

  private static List<String> targets(ToolCall call) {
    try {
      JsonNode root = JSON.readTree(call.argumentsJson());
      List<String> targets = new ArrayList<>();
      for (String field : List.of("path", "file", "target")) {
        if (root.path(field).isTextual()) {
          targets.add(root.path(field).asText());
        }
      }
      return List.copyOf(targets);
    } catch (IOException error) {
      return List.of();
    }
  }

  private static java.util.Optional<Rule> parse(Path path) {
    try {
      String text = Files.readString(path, StandardCharsets.UTF_8);
      if (!text.startsWith("---")) {
        return java.util.Optional.empty();
      }
      int end = text.indexOf("---", 3);
      if (end < 0) {
        return java.util.Optional.empty();
      }
      String frontMatter = text.substring(3, end);
      String pattern = value(frontMatter, "paths");
      String action = value(frontMatter, "action");
      if (pattern == null || !"deny".equalsIgnoreCase(action)) {
        return java.util.Optional.empty();
      }
      String reason = text.substring(end + 3).replaceAll("\\s+", " ").strip();
      PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim());
      Path fileName = Objects.requireNonNull(path.getFileName(), "rule path has no file name");
      return java.util.Optional.of(new Rule(fileName.toString(), matcher, reason));
    } catch (IOException | IllegalArgumentException error) {
      return java.util.Optional.empty();
    }
  }

  private static String value(String frontMatter, String key) {
    for (String line : frontMatter.split("\\R")) {
      int colon = line.indexOf(':');
      if (colon > 0 && key.equalsIgnoreCase(line.substring(0, colon).trim())) {
        return line.substring(colon + 1).trim();
      }
    }
    return null;
  }

  private record Rule(String source, PathMatcher matcher, String reason) {}
}
