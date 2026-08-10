package dev.miniclaudecode.runtime.node.workflow;

import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorkflowNodeSupport {
  private WorkflowNodeSupport() {}

  static MiniClaudeState merge(MiniClaudeState state, Map<String, Object> update) {
    Map<String, Object> data = new LinkedHashMap<>(state.data());
    data.putAll(update);
    return new MiniClaudeState(Map.copyOf(data));
  }

  static Map<String, Object> route(Map<String, Object> update, String route) {
    Map<String, Object> routed = new LinkedHashMap<>(update);
    routed.put(MiniClaudeState.WORKFLOW_ROUTE, route);
    return Map.copyOf(routed);
  }
}
