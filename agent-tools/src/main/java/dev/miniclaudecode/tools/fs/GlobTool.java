package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.internal.GlobMatcher;
import dev.miniclaudecode.tools.internal.ToolArguments;
import dev.miniclaudecode.tools.internal.ToolResults;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public final class GlobTool implements AgentTool {
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "glob",
          "Find workspace files by a portable glob pattern without following symbolic links",
          "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"maxResults\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"pattern\"]}",
          RiskLevel.LOW,
          ToolEffect.READ_ONLY_LOCAL);
  private final WorkspacePathResolver resolver;
  private final ToolResultStore resultStore;
  private final int defaultMaxResults;
  private final int inlineByteLimit;

  public GlobTool(WorkspacePathResolver resolver, ToolResultStore resultStore) {
    this(resolver, resultStore, 1000, 32768);
  }

  public GlobTool(
      WorkspacePathResolver resolver,
      ToolResultStore resultStore,
      int defaultMaxResults,
      int inlineByteLimit) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    if (defaultMaxResults >= 1 && inlineByteLimit >= 1) {
      this.defaultMaxResults = defaultMaxResults;
      this.inlineByteLimit = inlineByteLimit;
    } else {
      throw new IllegalArgumentException("glob limits must be positive");
    }
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      GlobMatcher matcher = new GlobMatcher(arguments.requiredText("pattern"));
      Path base = this.resolver.resolveExisting(arguments.optionalText("path", "."));
      if (!Files.isDirectory(base)) {
        throw new IllegalArgumentException("glob base path is not a directory");
      } else {
        int maxResults =
            arguments.optionalPositiveInt("maxResults", this.defaultMaxResults, 100000);

        String output;
        long matches;
        try (Stream<Path> paths = Files.walk(base)) {
          List<String> selected =
              paths
                  .filter(path -> !path.equals(base))
                  .filter(path -> !Files.isSymbolicLink(path))
                  .filter(x$0 -> Files.isRegularFile(x$0))
                  .filter(path -> matcher.matches(this.resolver.workspace().relativize(path)))
                  .sorted(Comparator.comparing(this.resolver::relativeDisplay))
                  .limit((long) maxResults)
                  .map(this.resolver::relativeDisplay)
                  .toList();
          matches = (long) selected.size();
          output = String.join("\n", selected);
        }

        return CompletableFuture.completedFuture(
            ToolResults.completed(
                call, output, Map.of("matches", matches), this.resultStore, this.inlineByteLimit));
      }
    } catch (IOException var15) {
      return CompletableFuture.completedFuture(
          ToolResults.failed(call, new IllegalArgumentException("glob search failed", var15)));
    } catch (RuntimeException var16) {
      return CompletableFuture.completedFuture(ToolResults.failed(call, var16));
    }
  }
}
