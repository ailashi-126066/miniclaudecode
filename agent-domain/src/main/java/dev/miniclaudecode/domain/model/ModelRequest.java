package dev.miniclaudecode.domain.model;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ModelRequest(
    String providerProfile,
    String modelName,
    List<AgentMessage> messages,
    List<ToolDescriptor> tools,
    boolean thinkingEnabled,
    int maxOutputTokens,
    Map<String, Object> attributes)
    implements Serializable {
  public ModelRequest(
      String providerProfile,
      String modelName,
      List<AgentMessage> messages,
      List<ToolDescriptor> tools,
      boolean thinkingEnabled,
      int maxOutputTokens,
      Map<String, Object> attributes) {
    providerProfile = requireText(providerProfile, "providerProfile");
    modelName = requireText(modelName, "modelName");
    messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
    tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    if (maxOutputTokens < 1) {
      throw new IllegalArgumentException("maxOutputTokens must be greater than zero");
    } else {
      attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
      this.providerProfile = providerProfile;
      this.modelName = modelName;
      this.messages = messages;
      this.tools = tools;
      this.thinkingEnabled = thinkingEnabled;
      this.maxOutputTokens = maxOutputTokens;
      this.attributes = attributes;
    }
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
