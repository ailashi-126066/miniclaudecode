package dev.miniclaudecode.prompt;

import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Data passed to prompt plugins without exposing CLI, provider, or persistence implementations. */
public record PromptBuildContext(
    Path workspace,
    List<ToolDescriptor> tools,
    String skillIndex,
    String outputProtocolInstruction,
    Map<String, Object> attributes) {
  public PromptBuildContext {
    workspace = Objects.requireNonNull(workspace, "workspace must not be null").normalize();
    tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    skillIndex = Objects.requireNonNullElse(skillIndex, "").strip();
    outputProtocolInstruction = Objects.requireNonNullElse(outputProtocolInstruction, "").strip();
    attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
  }
}
