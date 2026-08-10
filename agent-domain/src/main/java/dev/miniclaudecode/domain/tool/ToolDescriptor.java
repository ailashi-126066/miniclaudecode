package dev.miniclaudecode.domain.tool;

import dev.miniclaudecode.domain.approval.RiskLevel;
import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;

public record ToolDescriptor(
    String namespace,
    String name,
    String description,
    String inputSchemaJson,
    RiskLevel baseRisk,
    ToolEffect effect)
    implements Serializable {
  private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

  public ToolDescriptor(
      String namespace,
      String name,
      String description,
      String inputSchemaJson,
      RiskLevel baseRisk,
      ToolEffect effect) {
    namespace = requireName(namespace, "namespace");
    name = requireName(name, "name");
    description = requireText(description, "description");
    inputSchemaJson = requireText(inputSchemaJson, "inputSchemaJson");
    Objects.requireNonNull(baseRisk, "baseRisk must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
    this.namespace = namespace;
    this.name = name;
    this.description = description;
    this.inputSchemaJson = inputSchemaJson;
    this.baseRisk = baseRisk;
    this.effect = effect;
  }

  /** Compatibility constructor for extensions compiled against the original descriptor shape. */
  public ToolDescriptor(
      String namespace,
      String name,
      String description,
      String inputSchemaJson,
      RiskLevel baseRisk) {
    this(namespace, name, description, inputSchemaJson, baseRisk, ToolEffect.EXTERNAL_EFFECT);
  }

  public String qualifiedName() {
    return this.namespace + ":" + this.name;
  }

  private static String requireName(String value, String field) {
    String normalized = requireText(value, field);
    if (!NAME_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(field + " contains unsupported characters");
    } else {
      return normalized;
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
