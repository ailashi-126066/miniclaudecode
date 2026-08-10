package dev.miniclaudecode.persistence.config;

public record PlanningConfig(
    boolean enabled, int maxSteps, int maxAttemptsPerStep, int maxRevisions) {
  public PlanningConfig {
    if (maxSteps < 1 || maxSteps > 12) {
      throw new IllegalArgumentException("planning.max-steps must be between 1 and 12");
    }
    if (maxAttemptsPerStep < 1 || maxAttemptsPerStep > 5) {
      throw new IllegalArgumentException("planning.max-attempts-per-step must be between 1 and 5");
    }
    if (maxRevisions < 0 || maxRevisions > 10) {
      throw new IllegalArgumentException("planning.max-revisions must be between 0 and 10");
    }
  }

  public static PlanningConfig defaults() {
    return new PlanningConfig(true, 12, 2, 3);
  }
}
