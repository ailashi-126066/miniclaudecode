package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.internal.GlobMatcher;
import dev.miniclaudecode.tools.internal.TextFiles;
import dev.miniclaudecode.tools.internal.ToolArguments;
import dev.miniclaudecode.tools.internal.ToolResults;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class GrepTool implements AgentTool {
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "grep",
          "Search UTF-8 workspace files with a Java regular expression",
          "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"glob\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},\"maxResults\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"query\"]}",
          RiskLevel.LOW,
          ToolEffect.READ_ONLY_LOCAL);
  private final WorkspacePathResolver resolver;
  private final ToolResultStore resultStore;
  private final int defaultMaxResults;
  private final int inlineByteLimit;
  private final long maxFileBytes;

  public GrepTool(WorkspacePathResolver resolver, ToolResultStore resultStore) {
    this(resolver, resultStore, 1000, 32768, 2097152L);
  }

  public GrepTool(
      WorkspacePathResolver resolver,
      ToolResultStore resultStore,
      int defaultMaxResults,
      int inlineByteLimit,
      long maxFileBytes) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    if (defaultMaxResults >= 1 && inlineByteLimit >= 1 && maxFileBytes >= 1L) {
      this.defaultMaxResults = defaultMaxResults;
      this.inlineByteLimit = inlineByteLimit;
      this.maxFileBytes = maxFileBytes;
    } else {
      throw new IllegalArgumentException("grep limits must be positive");
    }
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      Pattern query = compile(arguments.requiredText("query"));
      GlobMatcher glob = new GlobMatcher(arguments.optionalText("glob", "**"));
      Path base = this.resolver.resolveExisting(arguments.optionalText("path", "."));
      if (!Files.isDirectory(base)) {
        throw new IllegalArgumentException("grep base path is not a directory");
      } else {
        int maxResults =
            arguments.optionalPositiveInt("maxResults", this.defaultMaxResults, 100000);
        List<String> matches = this.search(base, glob, query, maxResults);
        return CompletableFuture.completedFuture(
            ToolResults.completed(
                call,
                String.join("\n", matches),
                Map.of("matches", matches.size()),
                this.resultStore,
                this.inlineByteLimit));
      }
    } catch (IOException var9) {
      return CompletableFuture.completedFuture(
          ToolResults.failed(call, new IllegalArgumentException("grep search failed", var9)));
    } catch (RuntimeException var10) {
      return CompletableFuture.completedFuture(ToolResults.failed(call, var10));
    }
  }

  private List<String> search(Path base, GlobMatcher glob, Pattern query, int maxResults)
      throws IOException {
    List<Path> candidates;
    try (Stream<Path> paths = Files.walk(base)) {
      candidates =
          paths
              .filter(path -> !Files.isSymbolicLink(path))
              .filter(x$0 -> Files.isRegularFile(x$0))
              .filter(path -> glob.matches(this.resolver.workspace().relativize(path)))
              .filter(this::withinSizeLimit)
              .sorted(Comparator.comparing(this.resolver::relativeDisplay))
              .toList();
    }

    List<String> matches = new ArrayList<>();

    for (Path file : candidates) {
      String text;
      try {
        text = TextFiles.decodeUtf8(Files.readAllBytes(file));
      } catch (IllegalArgumentException var14) {
        continue;
      }

      String[] lines = text.split("\\R", -1);

      for (int index = 0; index < lines.length && matches.size() < maxResults; index++) {
        if (query.matcher(lines[index]).find()) {
          matches.add(
              this.resolver.relativeDisplay(file) + ":" + (index + 1) + ": " + lines[index]);
        }
      }

      if (matches.size() >= maxResults) {
        break;
      }
    }

    return matches;
  }

  private boolean withinSizeLimit(Path path) {
    try {
      return Files.size(path) <= this.maxFileBytes;
    } catch (IOException var3) {
      return false;
    }
  }

  private static Pattern compile(String expression) {
    try {
      return Pattern.compile(expression);
    } catch (PatternSyntaxException var2) {
      throw new IllegalArgumentException("invalid grep regular expression", var2);
    }
  }
}
