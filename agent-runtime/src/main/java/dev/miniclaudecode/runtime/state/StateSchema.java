package dev.miniclaudecode.runtime.state;

import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.session.AgentStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

public final class StateSchema {
  private StateSchema() {}

  public static Map<String, Channel<?>> channels() {
    Map<String, Channel<?>> channels = new LinkedHashMap<>();
    channels.put("messages", Channels.base(ArrayList::new));
    channels.put("modelEvents", Channels.base(ArrayList::new));
    channels.put("pendingToolCalls", Channels.base(ArrayList::new));
    channels.put("toolResults", Channels.base(ArrayList::new));
    channels.put("pendingApproval", Channels.base(() -> ""));
    channels.put("approvalDecision", Channels.base(() -> ""));
    channels.put("finalText", Channels.base(() -> ""));
    channels.put("thinking", Channels.base(() -> ""));
    channels.put("providerMetadata", Channels.base(() -> new LinkedHashMap<String, Object>()));
    channels.put("status", Channels.base(() -> AgentStatus.RUNNING));
    channels.put("error", Channels.base(() -> ""));
    channels.put("failureType", Channels.base(() -> ""));
    channels.put("failureRetryable", Channels.base(() -> false));
    channels.put("retryCount", Channels.base(() -> 0));
    channels.put("compactionCount", Channels.base(() -> 0));
    channels.put("modelSteps", Channels.base(() -> 0));
    channels.put("toolSteps", Channels.base(() -> 0));
    channels.put("verificationPrompts", Channels.base(() -> 0));
    channels.put("outputRepairCount", Channels.base(() -> 0));
    channels.put("plan", Channels.base(() -> ""));
    channels.put("planningPhase", Channels.base(() -> "DISCOVER"));
    channels.put("stepDecision", Channels.base(() -> ""));
    channels.put("trace", Channels.appenderWithDuplicate(ArrayList::new));
    return Map.copyOf(channels);
  }

  public static Map<String, Object> initialInput(ModelRequest request) {
    return Map.of("request", request, "messages", request.messages());
  }

  public static List<String> traceEntry(String node) {
    return List.of(node);
  }
}
