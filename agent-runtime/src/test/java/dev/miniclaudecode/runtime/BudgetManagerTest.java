package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetManagerTest {
  @Test
  void totalsProviderUsageAndStopsAtConfiguredCap() {
    Map<String, Object> attributes =
        Map.of(
            BudgetManager.MAX_COST_MICROS, 5L,
            BudgetManager.INPUT_RATE_MICROS_PER_MILLION, 1_000_000L,
            BudgetManager.OUTPUT_RATE_MICROS_PER_MILLION, 1_000_000L);
    long cost = BudgetManager.addUsage(attributes, Map.of(), 3, 2);

    assertThat(cost).isEqualTo(5L);
    assertThat(BudgetManager.exhausted(attributes, Map.of(BudgetManager.TOTAL_COST_MICROS, cost)))
        .isTrue();
  }

  @Test
  void reportsNotExhaustedWhenAccumulatedCostStaysUnderCap() {
    Map<String, Object> attributes =
        Map.of(
            BudgetManager.MAX_COST_MICROS, 10L,
            BudgetManager.INPUT_RATE_MICROS_PER_MILLION, 1_000_000L,
            BudgetManager.OUTPUT_RATE_MICROS_PER_MILLION, 1_000_000L);
    long cost = BudgetManager.addUsage(attributes, Map.of(), 3, 2);

    assertThat(cost).isEqualTo(5L);
    assertThat(BudgetManager.exhausted(attributes, Map.of(BudgetManager.TOTAL_COST_MICROS, cost)))
        .isFalse();
  }
}
