package dev.miniclaudecode.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import dev.miniclaudecode.rag.search.HybridCodeSearcher;
import dev.miniclaudecode.rag.search.SearchResult;
import dev.miniclaudecode.tools.internal.ToolResults;
import dev.miniclaudecode.tools.result.ToolResultStore;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class CodeSearchTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "workspace",
          "code_search",
          "Search the workspace code index with explainable BM25 and vector retrieval",
          "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"topK\":{\"type\":\"integer\",\"minimum\":1},\"tokenBudget\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"query\"]}",
          RiskLevel.LOW);
  private final LuceneCodeIndex index;
  private final HybridCodeSearcher searcher;
  private final Optional<ToolResultStore> resultStore;

  public CodeSearchTool(LuceneCodeIndex index, HybridCodeSearcher searcher) {
    this(index, searcher, Optional.empty());
  }

  public CodeSearchTool(
      LuceneCodeIndex index, HybridCodeSearcher searcher, ToolResultStore resultStore) {
    this(index, searcher, Optional.of(Objects.requireNonNull(resultStore)));
  }

  private CodeSearchTool(
      LuceneCodeIndex index, HybridCodeSearcher searcher, Optional<ToolResultStore> resultStore) {
    this.index = Objects.requireNonNull(index, "index must not be null");
    this.searcher = Objects.requireNonNull(searcher, "searcher must not be null");
    this.resultStore = Objects.requireNonNull(resultStore);
  }

  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode arguments = JSON.readTree(call.argumentsJson());
      String query = requiredText(arguments, "query");
      int topK = positiveInt(arguments, "topK", 8);
      int tokenBudget = positiveInt(arguments, "tokenBudget", 6000);
      this.index.synchronize(context.workspace());
      HybridCodeSearcher.SearchResponse response =
          this.searcher.search(
              query,
              new HybridCodeSearcher.SearchOptions(topK, tokenBudget, Math.max(40, topK * 4)));
      response = this.index.hydrateParentContext(response, tokenBudget);
      String output = render(response);
      Map<String, Object> metadata =
          Map.of(
              "results",
              response.results().size(),
              "estimatedTokens",
              response.estimatedTokens(),
              "bm25Candidates",
              response.bm25Hits().size(),
              "vectorCandidates",
              response.vectorHits().size(),
              "queryVariants",
              response.queryVariants());
      ToolResult result =
          this.resultStore
              .map(store -> ToolResults.completed(call, output, metadata, store, 4_096))
              .orElseGet(
                  () ->
                      new ToolResult(
                          call.toolCallId(), Status.COMPLETED, output, Optional.empty(), metadata));
      return CompletableFuture.completedFuture(result);
    } catch (RuntimeException | IOException var9) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              Status.FAILED,
              "code search failed: " + safeMessage(var9),
              Optional.empty(),
              Map.of()));
    }
  }

  private static String render(HybridCodeSearcher.SearchResponse response) {
    if (response.results().isEmpty()) {
      return "No relevant code found.";
    } else {
      StringBuilder output = new StringBuilder();

      for (SearchResult result : response.results()) {
        output
            .append('【')
            .append(result.chunk().path())
            .append(':')
            .append(result.chunk().startLine())
            .append('-')
            .append(result.chunk().endLine())
            .append('】')
            .append(" ")
            .append(result.chunk().symbol())
            .append(" [")
            .append(result.explanation())
            .append("]\n")
            .append(result.chunk().content())
            .append("\n\n");
      }

      return output.toString().stripTrailing();
    }
  }

  private static String requiredText(JsonNode arguments, String name) {
    JsonNode value = arguments.path(name);
    if (value.isTextual() && !value.asText().isBlank()) {
      return value.asText().trim();
    } else {
      throw new IllegalArgumentException(name + " must be a non-blank string");
    }
  }

  private static int positiveInt(JsonNode arguments, String name, int defaultValue) {
    JsonNode value = arguments.path(name);
    if (value.isMissingNode()) {
      return defaultValue;
    } else if (value.canConvertToInt() && value.asInt() >= 1) {
      return value.asInt();
    } else {
      throw new IllegalArgumentException(name + " must be a positive integer");
    }
  }

  private static String safeMessage(Exception exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }
}
