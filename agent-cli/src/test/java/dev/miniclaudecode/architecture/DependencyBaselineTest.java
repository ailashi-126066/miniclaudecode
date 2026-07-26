package dev.miniclaudecode.architecture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyBaselineTest {

  private static final Map<String, String> REQUIRED_TYPES =
      Map.of(
          "LangGraph4j", "org.bsc.langgraph4j.StateGraph",
          "LangChain4j", "dev.langchain4j.model.chat.StreamingChatModel",
          "Lucene", "org.apache.lucene.index.IndexWriter",
          "JavaParser", "com.github.javaparser.JavaParser",
          "JLine", "org.jline.terminal.TerminalBuilder");

  @Test
  void resolvesFrameworkBaseline() {
    REQUIRED_TYPES.forEach(
        (library, type) ->
            assertDoesNotThrow(
                () -> Class.forName(type),
                () -> library + " type is missing from the runtime classpath: " + type));
  }
}
