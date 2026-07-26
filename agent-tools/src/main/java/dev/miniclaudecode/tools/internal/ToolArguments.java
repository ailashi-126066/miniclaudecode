package dev.miniclaudecode.tools.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ToolArguments {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final JsonNode root;

  private ToolArguments(JsonNode root) {
    this.root = root;
  }

  public static ToolArguments parse(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      if (root != null && root.isObject()) {
        return new ToolArguments(root);
      } else {
        throw new IllegalArgumentException("tool arguments must be a JSON object");
      }
    } catch (JsonProcessingException var2) {
      throw new IllegalArgumentException("invalid tool arguments JSON", var2);
    }
  }

  public String requiredText(String name) {
    JsonNode value = this.root.get(name);
    if (value != null && value.isTextual() && !value.textValue().isBlank()) {
      return value.textValue();
    } else {
      throw new IllegalArgumentException("argument '" + name + "' must be non-blank text");
    }
  }

  public String requiredString(String name) {
    JsonNode value = this.root.get(name);
    if (value != null && value.isTextual()) {
      return value.textValue();
    } else {
      throw new IllegalArgumentException("argument '" + name + "' must be text");
    }
  }

  public String optionalText(String name, String defaultValue) {
    JsonNode value = this.root.get(name);
    if (value == null || value.isNull()) {
      return defaultValue;
    } else if (!value.isTextual()) {
      throw new IllegalArgumentException("argument '" + name + "' must be text");
    } else {
      return value.textValue();
    }
  }

  public int optionalPositiveInt(String name, int defaultValue, int maximum) {
    JsonNode value = this.root.get(name);
    if (value != null && !value.isNull()) {
      if (value.canConvertToInt() && value.intValue() >= 1 && value.intValue() <= maximum) {
        return value.intValue();
      } else {
        throw new IllegalArgumentException(
            "argument '" + name + "' must be between 1 and " + maximum);
      }
    } else {
      return defaultValue;
    }
  }

  public boolean optionalBoolean(String name, boolean defaultValue) {
    JsonNode value = this.root.get(name);
    if (value == null || value.isNull()) {
      return defaultValue;
    } else if (!value.isBoolean()) {
      throw new IllegalArgumentException("argument '" + name + "' must be boolean");
    } else {
      return value.booleanValue();
    }
  }
}
