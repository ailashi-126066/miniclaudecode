package dev.miniclaudecode.rag.eval;

import dev.miniclaudecode.rag.chunk.CodeChunk;
import dev.miniclaudecode.rag.chunk.CodeChunk.Kind;
import dev.miniclaudecode.rag.eval.RagEvaluator.EvaluationMetrics;
import dev.miniclaudecode.rag.eval.RagEvaluator.EvaluationReport;
import dev.miniclaudecode.rag.search.SearchResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class RagEvaluatorTest {
  @Test
  void loadsJsonlAndReportsRecallMrrAndLatencyPercentiles() throws Exception {
    Path fixture =
        Path.of(
            Objects.requireNonNull(this.getClass().getResource("/eval/java-fixture.jsonl"))
                .toURI());
    RagEvaluator evaluator = new RagEvaluator();
    EvaluationReport report =
        evaluator.evaluate(
            evaluator.load(fixture),
            Map.of(
                "hybrid",
                query ->
                    query.contains("alpha")
                        ? List.of(result("x"), result("a"))
                        : List.of(result("b"))));
    EvaluationMetrics metrics = (EvaluationMetrics) report.strategies().get("hybrid");
    Assertions.assertThat(metrics.recallAt5()).isEqualTo(1.0);
    Assertions.assertThat(metrics.recallAt10()).isEqualTo(1.0);
    Assertions.assertThat(metrics.meanReciprocalRank()).isEqualTo(0.75);
    Assertions.assertThat(metrics.p50LatencyMillis()).isGreaterThanOrEqualTo(0L);
    Assertions.assertThat(metrics.p95LatencyMillis())
        .isGreaterThanOrEqualTo(metrics.p50LatencyMillis());
    Assertions.assertThat(metrics.cases()).isEqualTo(2);
  }

  private static SearchResult result(String id) {
    CodeChunk chunk =
        new CodeChunk(id, id + ".java", "java", Kind.TYPE, "", id, id, 1, 1, "class " + id + " {}");
    return new SearchResult(chunk, 0.1, Map.of(), Map.of());
  }
}
