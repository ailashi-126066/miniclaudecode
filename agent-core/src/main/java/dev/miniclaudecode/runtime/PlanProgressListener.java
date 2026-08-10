package dev.miniclaudecode.runtime;

import dev.miniclaudecode.planning.Plan;

@FunctionalInterface
public interface PlanProgressListener {
  void onPlanChanged(String event, Plan plan);

  static PlanProgressListener noOp() {
    return (event, plan) -> {};
  }
}
