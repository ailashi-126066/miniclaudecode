package dev.miniclaudecode.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyBaselineTest {

  private static final Map<String, String> REQUIRED_TYPES =
      Map.of(
          "Explicit AgentLoop", "dev.miniclaudecode.runtime.AgentLoop",
          "LangChain4j", "dev.langchain4j.model.chat.StreamingChatModel",
          "Lucene", "org.apache.lucene.index.IndexWriter",
          "JavaParser", "com.github.javaparser.JavaParser",
          "Tree-sitter", "org.treesitter.TSParser",
          "TUI4J", "com.williamcallahan.tui4j.compat.bubbletea.Program");

  @Test
  void resolvesFrameworkBaseline() {
    REQUIRED_TYPES.forEach(
        (library, type) ->
            assertDoesNotThrow(
                () -> Class.forName(type),
                () -> library + " type is missing from the runtime classpath: " + type));
  }
}
