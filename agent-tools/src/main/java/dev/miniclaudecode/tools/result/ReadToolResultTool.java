package dev.miniclaudecode.tools.result;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.tools.internal.ToolArguments;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Retrieves a bounded slice of a large tool output that was replaced by a content-addressed
 * placeholder during context compaction.
 */
public final class ReadToolResultTool implements AgentTool {
  private static final int DEFAULT_CHARACTERS = 16_384;
  private static final int MAX_CHARACTERS = 65_536;
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "context",
          "read_result",
          "Read a bounded slice of an externalized tool result by its sha256 reference",
          "{\"type\":\"object\",\"properties\":{\"reference\":{\"type\":\"string\",\"pattern\":\"^sha256:[0-9a-f]{64}$\"},\"offset\":{\"type\":\"integer\",\"minimum\":0},\"maxCharacters\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":65536}},\"required\":[\"reference\"]}",
          RiskLevel.LOW,
          ToolEffect.READ_ONLY_LOCAL);
  private final ToolResultStore store;

  public ReadToolResultTool(ToolResultStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      ToolArguments arguments = ToolArguments.parse(call.argumentsJson());
      String reference = arguments.requiredText("reference");
      int offset = arguments.optionalNonNegativeInt("offset", 0, Integer.MAX_VALUE);
      int maximum =
          arguments.optionalPositiveInt("maxCharacters", DEFAULT_CHARACTERS, MAX_CHARACTERS);
      String content = this.store.read(reference);
      if (offset > content.length()) {
        throw new IllegalArgumentException("offset exceeds tool result length");
      }
      int end = (int) Math.min(content.length(), (long) offset + maximum);
      String slice = content.substring(offset, end);
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              slice,
              Optional.of(reference),
              Map.of(
                  "reference",
                  reference,
                  "offset",
                  offset,
                  "nextOffset",
                  end,
                  "totalCharacters",
                  content.length(),
                  "hasMore",
                  end < content.length())));
    } catch (RuntimeException error) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              "tool result retrieval failed: "
                  + Objects.requireNonNullElse(
                      error.getMessage(), error.getClass().getSimpleName()),
              Optional.empty(),
              Map.of()));
    }
  }
}
