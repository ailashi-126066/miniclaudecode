package dev.miniclaudecode.runtime;

import java.util.Map;

/** Enforces an opt-in per-turn cost cap using provider-reported token usage. */
public final class BudgetManager {
  public static final String MAX_COST_MICROS = "budget.maxCostMicros";
  public static final String INPUT_RATE_MICROS_PER_MILLION = "budget.inputMicrosPerMillion";
  public static final String OUTPUT_RATE_MICROS_PER_MILLION = "budget.outputMicrosPerMillion";
  public static final String TOTAL_COST_MICROS = "budget.totalCostMicros";

  private BudgetManager() {}

  public static boolean exhausted(Map<String, Object> attributes, Map<String, Object> metadata) {
    long maximum = number(attributes.get(MAX_COST_MICROS));
    return maximum > 0 && number(metadata.get(TOTAL_COST_MICROS)) >= maximum;
  }

  public static long addUsage(
      Map<String, Object> attributes,
      Map<String, Object> metadata,
      long inputTokens,
      long outputTokens) {
    long inputRate = number(attributes.get(INPUT_RATE_MICROS_PER_MILLION));
    long outputRate = number(attributes.get(OUTPUT_RATE_MICROS_PER_MILLION));
    long usageCost =
        Math.addExact(
            Math.multiplyExact(inputTokens, inputRate) / 1_000_000L,
            Math.multiplyExact(outputTokens, outputRate) / 1_000_000L);
    return Math.addExact(number(metadata.get(TOTAL_COST_MICROS)), usageCost);
  }

  private static long number(Object value) {
    return value instanceof Number number && number.longValue() > 0 ? number.longValue() : 0L;
  }
}
