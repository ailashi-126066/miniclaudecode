package dev.miniclaudecode.rag.chunk;

import java.util.List;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class StructuredTextChunkerTest {
  @Test
  void keepsMarkdownHeadingsAsSemanticSections() {
    String markdown = "intro\n# Install\nstep one\nstep two\n## Windows\npowershell\n";
    List<CodeChunk> chunks = new StructuredTextChunker(20, 2).chunk("README.md", markdown);
    Assertions.assertThat(chunks).hasSize(3);
    Assertions.assertThat(chunks)
        .extracting(CodeChunk::symbol)
        .containsExactly(new String[] {"", "Install", "Windows"});
    Assertions.assertThat(chunks.get(1).startLine()).isEqualTo(2);
    Assertions.assertThat(chunks.get(1).endLine()).isEqualTo(4);
    Assertions.assertThat(chunks.get(1).content()).isEqualTo("# Install\nstep one\nstep two");
  }

  @Test
  void overlapsLongPlainTextWindows() {
    String text =
        String.join("\n", IntStream.rangeClosed(1, 12).mapToObj(i -> "line " + i).toList());
    List<CodeChunk> chunks = new StructuredTextChunker(5, 2).chunk("notes.txt", text);
    Assertions.assertThat(chunks)
        .extracting(CodeChunk::startLine)
        .containsExactly(new Integer[] {1, 4, 7, 10});
    Assertions.assertThat(chunks)
        .extracting(CodeChunk::endLine)
        .containsExactly(new Integer[] {5, 8, 11, 12});
  }
}
