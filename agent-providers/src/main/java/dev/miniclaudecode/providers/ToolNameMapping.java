package dev.miniclaudecode.providers;

import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record ToolNameMapping(
    Map<String, String> qualifiedToProvider, Map<String, String> providerToQualified) {

  static ToolNameMapping from(List<ToolDescriptor> tools) {
    Map<String, String> forward = new LinkedHashMap<>();
    Map<String, String> reverse = new LinkedHashMap<>();
    for (int index = 0; index < tools.size(); index++) {
      ToolDescriptor tool = tools.get(index);
      String safe =
          "tool_"
              + index
              + "_"
              + tool.namespace().replaceAll("[^A-Za-z0-9_-]", "_")
              + "_"
              + tool.name().replaceAll("[^A-Za-z0-9_-]", "_");
      forward.put(tool.qualifiedName(), safe);
      reverse.put(safe, tool.qualifiedName());
    }
    return new ToolNameMapping(Map.copyOf(forward), Map.copyOf(reverse));
  }

  String providerName(String qualifiedName) {
    return qualifiedToProvider.getOrDefault(qualifiedName, sanitizeUnknown(qualifiedName));
  }

  String qualifiedName(String providerName) {
    return providerToQualified.getOrDefault(providerName, providerName);
  }

  private static String sanitizeUnknown(String value) {
    return value.replaceAll("[^A-Za-z0-9_-]", "_");
  }
}
