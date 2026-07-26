package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.internal.ToolArguments;
import dev.miniclaudecode.tools.internal.ToolResults;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

public final class ListTool implements AgentTool {
  private static final int DEFAULT_MAX_ENTRIES = 500;
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "list",
          "List files and directories immediately below a workspace directory",
          "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"maxEntries\":{\"type\":\"integer\",\"minimum\":1}}}",
          RiskLevel.LOW);
  private final WorkspacePathResolver resolver;
  private final ToolResultStore resultStore;
  private final int inlineByteLimit;

  public ListTool(WorkspacePathResolver resolver, ToolResultStore resultStore) {
    this(resolver, resultStore, 32768);
  }

  public ListTool(
      WorkspacePathResolver resolver, ToolResultStore resultStore, int inlineByteLimit) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    if (inlineByteLimit < 1) {
      throw new IllegalArgumentException("inlineByteLimit must be positive");
    } else {
      this.inlineByteLimit = inlineByteLimit;
    }
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      Path directory = this.resolver.resolveExisting(arguments.optionalText("path", "."));
      if (!Files.isDirectory(directory)) {
        throw new IllegalArgumentException("path is not a directory");
      } else {
        int maximum = arguments.optionalPositiveInt("maxEntries", 500, 10000);

        String output;
        try (Stream<Path> entries = Files.list(directory)) {
          output =
              entries
                  .filter(path -> !Files.isSymbolicLink(path))
                  .sorted(Comparator.comparing(this.resolver::relativeDisplay))
                  .limit((long) maximum)
                  .map(
                      path ->
                          (Files.isDirectory(path) ? "dir  " : "file ")
                              + this.resolver.relativeDisplay(path))
                  .reduce((left, right) -> left + "\n" + right)
                  .orElse("(empty directory)");
        }

        return CompletableFuture.completedFuture(
            ToolResults.completed(
                call,
                output,
                Map.of("path", this.resolver.relativeDisplay(directory)),
                this.resultStore,
                this.inlineByteLimit));
      }
    } catch (IOException var12) {
      return CompletableFuture.completedFuture(
          ToolResults.failed(
              call, new IllegalArgumentException("failed to list directory", var12)));
    } catch (RuntimeException var13) {
      return CompletableFuture.completedFuture(ToolResults.failed(call, var13));
    }
  }
}
