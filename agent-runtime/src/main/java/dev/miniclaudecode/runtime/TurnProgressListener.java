package dev.miniclaudecode.runtime;

import java.util.Objects;

/**
 * Observes durable boundaries inside the normal turn loop without turning each boundary into a
 * graph node. Observers must treat notifications as best-effort: an audit or UI failure must not
 * change an agent turn's outcome.
 */
@FunctionalInterface
public interface TurnProgressListener {
  void onProgress(Progress progress);

  static TurnProgressListener noOp() {
    return ignored -> {};
  }

  record Progress(
      String phase,
      int modelSteps,
      int toolSteps,
      int compactionCount,
      int estimatedInputTokens,
      int inputBudgetTokens,
      String compactionReason,
      int beforeCompactionTokens) {
    public Progress {
      phase = requireText(phase, "phase");
      if (modelSteps < 0
          || toolSteps < 0
          || compactionCount < 0
          || estimatedInputTokens < 0
          || inputBudgetTokens < 0
          || beforeCompactionTokens < 0) {
        throw new IllegalArgumentException("turn progress values must not be negative");
      }
      compactionReason = compactionReason == null ? "" : compactionReason;
    }

    public boolean compaction() {
      return "compaction".equals(phase);
    }

    private static String requireText(String value, String name) {
      value = Objects.requireNonNull(value, name + " must not be null").trim();
      if (value.isEmpty()) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
      return value;
    }
  }
}
