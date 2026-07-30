package dev.miniclaudecode.rag.search;

import dev.miniclaudecode.rag.FakeEmbeddingModel;
import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import dev.miniclaudecode.rag.search.HybridCodeSearcher.SearchOptions;
import dev.miniclaudecode.rag.search.HybridCodeSearcher.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridCodeSearcherTest {
  @TempDir Path temporaryDirectory;
  private HybridCodeSearcher searcher;

  @BeforeEach
  void setUp() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Files.writeString(
        workspace.resolve("OrderService.java"),
        "class OrderService { void cancelOrder() { audit(); } void audit() {} }\n");
    Files.writeString(
        workspace.resolve("PaymentGateway.java"),
        "class PaymentGateway { void refundPayment() { notifyBank(); } void notifyBank() {} }\n");
    FakeEmbeddingModel embeddings = new FakeEmbeddingModel();
    LuceneCodeIndex index =
        new LuceneCodeIndex(this.temporaryDirectory.resolve("index"), embeddings);
    index.synchronize(workspace);
    this.searcher =
        new HybridCodeSearcher(
            new Bm25Retriever(index.luceneDirectory()),
            new VectorRetriever(index.luceneDirectory(), embeddings));
  }

  @Test
  void combinesLexicalAndVectorCandidatesWithIdentifierAwareBoosts() throws Exception {
    SearchResponse response = this.searcher.search("cancel order implementation");
    Assertions.assertThat(response.results()).isNotEmpty();
    Assertions.assertThat(((SearchResult) response.results().getFirst()).chunk().symbol())
        .isEqualTo("cancelOrder()");
    Assertions.assertThat(response.results())
        .extracting(result -> result.chunk().id())
        .doesNotHaveDuplicates();
    Assertions.assertThat(response.bm25Hits()).isNotEmpty();
    Assertions.assertThat(response.vectorHits()).isNotEmpty();
    Assertions.assertThat(response.explain())
        .contains(
            new CharSequence[] {
              "BM25 candidates", "vector candidates", "RRF=", "OrderService.java"
            });
  }

  @Test
  void enforcesTopKAndContextTokenBudget() throws Exception {
    SearchResponse response = this.searcher.search("service", new SearchOptions(2, 200, 8));
    Assertions.assertThat(response.results()).hasSizeLessThanOrEqualTo(2);
    Assertions.assertThat(response.estimatedTokens()).isLessThanOrEqualTo(200);
  }

  @Test
  void expandsCamelCaseIdentifiersWithoutDroppingTheOriginalQuestion() {
    CodeQueryRewriter.QueryPlan plan =
        new CodeQueryRewriter().rewrite("where is cancelOrder used?");
    Assertions.assertThat(plan.variants())
        .containsExactly("where is cancelOrder used?", "where is cancel order used");
  }
}
