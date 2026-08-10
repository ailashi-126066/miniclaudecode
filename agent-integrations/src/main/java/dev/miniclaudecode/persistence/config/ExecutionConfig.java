package dev.miniclaudecode.persistence.config;

import java.util.Locale;

/** Hard bounds for the hybrid Direct-ReAct / Plan-and-Execute workflow. */
public record ExecutionConfig(
    String mode, int maxDirectAttempts, int maxStepAttempts, int maxReplans) {
  public ExecutionConfig {
    mode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    if (!"hybrid".equals(mode)) {
      throw new IllegalArgumentException("execution.mode must be hybrid");
    }
    if (maxDirectAttempts < 1 || maxDirectAttempts > 2) {
      throw new IllegalArgumentException("execution.max-direct-attempts must be between 1 and 2");
    }
    if (maxStepAttempts < 1 || maxStepAttempts > 2) {
      throw new IllegalArgumentException("execution.max-step-attempts must be between 1 and 2");
    }
    if (maxReplans < 0 || maxReplans > 1) {
      throw new IllegalArgumentException("execution.max-replans must be between 0 and 1");
    }
  }

  public static ExecutionConfig defaults() {
    return new ExecutionConfig("hybrid", 2, 2, 1);
  }
}
