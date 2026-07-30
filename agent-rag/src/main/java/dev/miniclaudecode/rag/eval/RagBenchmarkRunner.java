package dev.miniclaudecode.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned offline retrieval evaluation with a machine-readable report and regression gate.
 *
 * <p>A benchmark directory contains {@code manifest.json} and one or more {@code <split>.jsonl}
 * files. Keeping the split and dataset version in every report makes metric changes reviewable,
 * rather than treating a one-off local run as evidence of RAG quality.
 */
public final class RagBenchmarkRunner {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final RagEvaluator evaluator;

  public RagBenchmarkRunner() {
    this(new RagEvaluator());
  }

  public RagBenchmarkRunner(RagEvaluator evaluator) {
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
  }

  public BenchmarkReport run(
      Path datasetDirectory,
      String split,
      Map<String, RagEvaluator.SearchStrategy> strategies,
      Path reportFile)
      throws IOException {
    DatasetManifest manifest =
        JSON.readValue(datasetDirectory.resolve("manifest.json").toFile(), DatasetManifest.class);
    List<RagEvaluator.EvaluationCase> cases =
        this.evaluator.load(datasetDirectory.resolve(split + ".jsonl"));
    RagEvaluator.EvaluationReport metrics = this.evaluator.evaluate(cases, strategies);
    BenchmarkReport report =
        new BenchmarkReport(
            manifest.version(), split, manifest.description(), Instant.now().toString(), metrics);
    Path parent =
        Objects.requireNonNull(reportFile.toAbsolutePath().getParent(), "report needs a parent");
    Files.createDirectories(parent);
    JSON.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
    return report;
  }

  /** Fails a CI run when a named strategy drops below its reviewed baseline. */
  public static void requireAtLeast(BenchmarkReport report, Map<String, MinimumMetrics> baseline) {
    for (Map.Entry<String, MinimumMetrics> expected : baseline.entrySet()) {
      RagEvaluator.EvaluationMetrics actual = report.metrics().strategies().get(expected.getKey());
      if (actual == null) {
        throw new IllegalArgumentException("report has no strategy named " + expected.getKey());
      }
      MinimumMetrics minimum = expected.getValue();
      if (actual.recallAt5() < minimum.recallAt5()
          || actual.recallAt10() < minimum.recallAt10()
          || actual.meanReciprocalRank() < minimum.meanReciprocalRank()) {
        throw new IllegalStateException(
            "RAG regression for "
                + expected.getKey()
                + ": expected at least "
                + minimum
                + ", got "
                + actual);
      }
    }
  }

  public record DatasetManifest(String version, String description) {
    public DatasetManifest {
      if (version == null || version.isBlank()) {
        throw new IllegalArgumentException("dataset version must not be blank");
      }
      description = Objects.requireNonNullElse(description, "");
    }
  }

  public record BenchmarkReport(
      String datasetVersion,
      String split,
      String description,
      String generatedAt,
      RagEvaluator.EvaluationReport metrics) {}

  public record MinimumMetrics(double recallAt5, double recallAt10, double meanReciprocalRank) {
    public MinimumMetrics {
      if (recallAt5 < 0.0
          || recallAt10 < 0.0
          || meanReciprocalRank < 0.0
          || recallAt5 > 1.0
          || recallAt10 > 1.0
          || meanReciprocalRank > 1.0) {
        throw new IllegalArgumentException("minimum metrics must be between zero and one");
      }
    }
  }
}
