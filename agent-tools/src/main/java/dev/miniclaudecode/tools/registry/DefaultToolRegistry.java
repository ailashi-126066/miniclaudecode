package dev.miniclaudecode.tools.registry;

import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultToolRegistry implements AgentToolRegistry {
  private final Map<String, AgentTool> toolsByQualifiedName;
  private final Map<String, List<AgentTool>> toolsByShortName;

  public DefaultToolRegistry(Collection<? extends AgentTool> tools) {
    Objects.requireNonNull(tools, "tools must not be null");
    Map<String, AgentTool> qualified = new LinkedHashMap<>();
    Map<String, List<AgentTool>> shortNames = new LinkedHashMap<>();

    for (AgentTool tool : tools) {
      AgentTool nonNullTool = Objects.requireNonNull(tool, "tool must not be null");
      ToolDescriptor descriptor = nonNullTool.descriptor();
      String qualifiedName = descriptor.qualifiedName();
      if (qualified.putIfAbsent(qualifiedName, nonNullTool) != null) {
        throw new IllegalArgumentException("duplicate tool name: " + qualifiedName);
      }

      shortNames.computeIfAbsent(descriptor.name(), ignored -> new ArrayList<>()).add(nonNullTool);
    }

    this.toolsByQualifiedName = Map.copyOf(qualified);
    Map<String, List<AgentTool>> immutableShortNames = new LinkedHashMap<>();
    shortNames.forEach((name, matches) -> immutableShortNames.put(name, List.copyOf(matches)));
    this.toolsByShortName = Map.copyOf(immutableShortNames);
  }

  public AgentTool require(String name) {
    String requested = requireText(name);
    if (requested.contains(":")) {
      AgentTool tool = this.toolsByQualifiedName.get(requested);
      if (tool == null) {
        throw new IllegalArgumentException("unknown tool: " + requested);
      } else {
        return tool;
      }
    } else {
      List<AgentTool> matches = this.toolsByShortName.getOrDefault(requested, List.of());
      if (matches.isEmpty()) {
        throw new IllegalArgumentException("unknown tool: " + requested);
      } else if (matches.size() > 1) {
        String candidates =
            matches.stream()
                .map(tool -> tool.descriptor().qualifiedName())
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        throw new IllegalArgumentException(
            "ambiguous tool name '" + requested + "'; use one of: " + candidates);
      } else {
        return matches.getFirst();
      }
    }
  }

  public java.util.Optional<AgentTool> find(String qualifiedName) {
    return java.util.Optional.ofNullable(this.toolsByQualifiedName.get(qualifiedName));
  }

  public List<ToolDescriptor> descriptors() {
    return this.toolsByQualifiedName.values().stream()
        .<ToolDescriptor>map(AgentTool::descriptor)
        .sorted(Comparator.comparing(ToolDescriptor::qualifiedName))
        .toList();
  }

  private static String requireText(String name) {
    if (name != null && !name.isBlank()) {
      return name.trim();
    } else {
      throw new IllegalArgumentException("tool name must not be blank");
    }
  }
}
