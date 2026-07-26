package dev.miniclaudecode.rag.tool;

import dev.miniclaudecode.domain.event.EventSink;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.domain.tool.ToolResult.Status;
import dev.miniclaudecode.rag.FakeEmbeddingModel;
import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import dev.miniclaudecode.rag.search.Bm25Retriever;
import dev.miniclaudecode.rag.search.HybridCodeSearcher;
import dev.miniclaudecode.rag.search.VectorRetriever;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.MapAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeSearchToolTest {
  @TempDir Path temporaryDirectory;

  @Test
  void lazilyIndexesWorkspaceAndReturnsAuditableHybridEvidence() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Files.writeString(
        workspace.resolve("AccountService.java"),
        "class AccountService { void deactivateAccount() { audit(); } void audit() {} }\n");
    FakeEmbeddingModel embeddings = new FakeEmbeddingModel();
    LuceneCodeIndex index =
        new LuceneCodeIndex(this.temporaryDirectory.resolve("index"), embeddings);
    HybridCodeSearcher searcher =
        new HybridCodeSearcher(
            new Bm25Retriever(index.luceneDirectory()),
            new VectorRetriever(index.luceneDirectory(), embeddings));
    CodeSearchTool tool = new CodeSearchTool(index, searcher);
    ToolResult result =
        (ToolResult)
            tool.execute(
                    new ToolCall(
                        "call-1",
                        "workspace:code_search",
                        "{\"query\":\"deactivate account\",\"topK\":3}"),
                    context(workspace))
                .toCompletableFuture()
                .get();
    Assertions.assertThat(result.status()).isEqualTo(Status.COMPLETED);
    Assertions.assertThat(result.summary())
        .contains(
            new CharSequence[] {
              "AccountService.java", "deactivateAccount()", "BM25 rank=", "vector rank="
            });
    ((MapAssert) Assertions.assertThat(result.metadata()).containsEntry("results", 3))
        .containsKeys(new String[] {"estimatedTokens", "bm25Candidates", "vectorCandidates"});
  }

  private static ToolContext context(Path workspace) {
    return new ToolContext(
        new SessionId("session-1"), new TurnId(1L), workspace, EventSink.NOOP, Map.of());
  }
}
