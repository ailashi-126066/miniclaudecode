package dev.miniclaudecode.planning;

import dev.miniclaudecode.domain.session.SessionId;
import java.util.Optional;

public interface PlanRepository {
  void save(SessionId sessionId, Plan plan);

  Optional<Plan> load(SessionId sessionId);
}
