package dev.miniclaudecode.planning;

import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface TaskPlanner {
  CompletionStage<Plan> createPlan(PlanningInput input, ModelRequest parentRequest);
}
