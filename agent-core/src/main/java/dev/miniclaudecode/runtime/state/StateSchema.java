package dev.miniclaudecode.runtime.state;

import dev.miniclaudecode.domain.model.ModelRequest;
import java.util.List;
import java.util.Map;

public final class StateSchema {
  private StateSchema() {}

  public static Map<String, Object> initialInput(ModelRequest request) {
    return Map.of("request", request, "messages", request.messages());
  }

  public static List<String> traceEntry(String node) {
    return List.of(node);
  }
}
