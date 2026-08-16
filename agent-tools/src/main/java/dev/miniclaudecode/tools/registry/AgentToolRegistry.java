package dev.miniclaudecode.tools.registry;

import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.util.List;
import java.util.Optional;

/** Common lookup surface for eager-only and session-aware deferred registries. */
public interface AgentToolRegistry {
  AgentTool require(String name);

  default AgentTool require(SessionId sessionId, String name) {
    return require(name);
  }

  Optional<AgentTool> find(String qualifiedName);

  List<ToolDescriptor> descriptors();

  default List<ToolDescriptor> descriptors(SessionId sessionId) {
    return descriptors();
  }

  default List<ToolDescriptor> allDescriptors() {
    return descriptors();
  }
}
