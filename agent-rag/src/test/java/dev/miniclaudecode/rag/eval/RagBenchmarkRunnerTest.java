package dev.miniclaudecode.rag.eval;

import dev.miniclaudecode.rag.chunk.CodeChunk;
import dev.miniclaudecode.rag.chunk.CodeChunk.Kind;
import dev.miniclaudecode.rag.search.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagBenchmarkRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void writesVersionedReportAndRejectsMetricRegressions() throws Exception {
    Path dataset =
        Path.of(getClass().getResource("/eval/benchmark-v1/manifest.json").toURI()).getParent();
    Path reportFile = this.temporaryDirectory.resolve("reports/retrieval.json");
    SearchResult order =
        result("OrderService.java:2:cancelOrder()", "OrderService.java", "cancelOrder()");
    SearchResult payment =
        result("PaymentGateway.java:4:refundPayment()", "PaymentGateway.java", "refundPayment()");

    RagBenchmarkRunner.BenchmarkReport report =
        new RagBenchmarkRunner()
            .run(
                dataset,
                "test",
                Map.of(
                    "perfect",
                    query -> query.contains("cancel") ? List.of(order) : List.of(payment)),
                reportFile);

    Assertions.assertThat(report.datasetVersion()).isEqualTo("benchmark-v1");
    Assertions.assertThat(Files.readString(reportFile)).contains("recallAt5", "benchmark-v1");
    RagBenchmarkRunner.requireAtLeast(
        report, Map.of("perfect", new RagBenchmarkRunner.MinimumMetrics(1.0, 1.0, 1.0)));
    Assertions.assertThatIllegalStateException()
        .isThrownBy(
            () ->
                RagBenchmarkRunner.requireAtLeast(
                    new RagBenchmarkRunner.BenchmarkReport(
                        "benchmark-v1",
                        "test",
                        "",
                        "",
                        new RagEvaluator.EvaluationReport(
                            Map.of(
                                "perfect",
                                new RagEvaluator.EvaluationMetrics(1.0, 1.0, 0.9, 0, 0, 2)))),
                    Map.of("perfect", new RagBenchmarkRunner.MinimumMetrics(1.0, 1.0, 1.0))));
  }

  private static SearchResult result(String id, String path, String symbol) {
    CodeChunk chunk =
        new CodeChunk(
            id, path, "java", Kind.METHOD, "", "", symbol, 1, 2, "void " + symbol + " {}");
    return new SearchResult(chunk, 1.0, Map.of(), Map.of());
  }
}
