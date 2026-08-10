package dev.miniclaudecode.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.domain.model.ModelStreamEvent;
import org.junit.jupiter.api.Test;

class StreamEventAssemblerTest {

  @Test
  void assemblesInterleavedToolArgumentFragmentsByCallId() {
    StreamEventAssembler assembler = new StreamEventAssembler();

    assembler.startToolCall("call-a", "workspace.read_file");
    assembler.startToolCall("call-b", "workspace.grep");
    assembler.appendToolArguments("call-a", "{\"path\":");
    assembler.appendToolArguments("call-b", "{\"query\":\"TODO\"}");
    assembler.appendToolArguments("call-a", "\"README.md\"}");

    ModelStreamEvent.ToolCallCompleted second = assembler.completeToolCall("call-b");
    ModelStreamEvent.ToolCallCompleted first = assembler.completeToolCall("call-a");

    assertThat(first.toolCall().qualifiedName()).isEqualTo("workspace.read_file");
    assertThat(first.toolCall().argumentsJson()).isEqualTo("{\"path\":\"README.md\"}");
    assertThat(second.toolCall().argumentsJson()).isEqualTo("{\"query\":\"TODO\"}");
    assertThat(assembler.hasPendingToolCalls()).isFalse();
  }

  @Test
  void rejectsUnknownDuplicateAndUnfinishedToolCalls() {
    StreamEventAssembler assembler = new StreamEventAssembler();
    assembler.startToolCall("call-a", "workspace.read_file");

    assertThatThrownBy(() -> assembler.startToolCall("call-a", "workspace.grep"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("call-a");
    assertThatThrownBy(() -> assembler.appendToolArguments("missing", "{}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing");
    assertThatThrownBy(assembler::verifyComplete)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("call-a");
  }
}
