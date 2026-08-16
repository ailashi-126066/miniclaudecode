package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.diff.FileHashes;
import dev.miniclaudecode.tools.internal.TextFiles;
import dev.miniclaudecode.tools.internal.ToolArguments;
import dev.miniclaudecode.tools.internal.ToolResults;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ReadTool implements AgentTool {
  private static final int DEFAULT_MAX_BYTES = 524288;
  private static final int DEFAULT_INLINE_BYTES = 32768;
  private static final int MAX_LINES = 10000;
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "read",
          "Read a UTF-8 text file inside the workspace with stable line numbers",
          "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"startLine\":{\"type\":\"integer\",\"minimum\":1},\"maxLines\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"path\"]}",
          RiskLevel.LOW,
          ToolEffect.READ_ONLY_LOCAL);
  private final WorkspacePathResolver resolver;
  private final ToolResultStore resultStore;
  private final int maxBytes;
  private final int inlineByteLimit;

  public ReadTool(WorkspacePathResolver resolver, ToolResultStore resultStore) {
    this(resolver, resultStore, 524288, 32768);
  }

  public ReadTool(
      WorkspacePathResolver resolver,
      ToolResultStore resultStore,
      int maxBytes,
      int inlineByteLimit) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    this.resultStore = Objects.requireNonNull(resultStore, "resultStore must not be null");
    if (maxBytes >= 1 && inlineByteLimit >= 1 && inlineByteLimit <= maxBytes) {
      this.maxBytes = maxBytes;
      this.inlineByteLimit = inlineByteLimit;
    } else {
      throw new IllegalArgumentException("read limits are invalid");
    }
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(context, "context must not be null");

    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      String requestedPath = arguments.requiredText("path");
      int startLine = arguments.optionalPositiveInt("startLine", 1, Integer.MAX_VALUE);
      int maxLines = arguments.optionalPositiveInt("maxLines", 10000, 10000);
      Path file = this.resolver.resolveExisting(requestedPath);
      if (!Files.isRegularFile(file)) {
        throw new IllegalArgumentException("path is not a regular file: " + requestedPath);
      } else {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
          bytes = input.readNBytes(this.maxBytes + 1);
        }

        boolean fileTruncated = bytes.length > this.maxBytes;
        if (fileTruncated) {
          bytes = Arrays.copyOf(bytes, this.maxBytes);
        }

        String text = TextFiles.decodeUtf8(bytes);
        String output = TextFiles.withLineNumbers(text, startLine, maxLines);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("path", this.resolver.relativeDisplay(file));
        metadata.put("startLine", startLine);
        metadata.put("endLine", startLine + Math.max(0, output.lines().toList().size() - 1));
        metadata.put("contentHash", "sha256:" + FileHashes.sha256(bytes));
        metadata.put("hashedBytes", bytes.length);
        metadata.put("fileTruncated", fileTruncated);
        ToolResult result =
            ToolResults.completed(call, output, metadata, this.resultStore, this.inlineByteLimit);
        return CompletableFuture.completedFuture(result);
      }
    } catch (IOException var16) {
      return CompletableFuture.completedFuture(
          ToolResults.failed(
              call, new IllegalArgumentException("failed to read workspace file", var16)));
    } catch (RuntimeException var17) {
      return CompletableFuture.completedFuture(ToolResults.failed(call, var17));
    }
  }
}
