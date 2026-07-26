package dev.miniclaudecode.rag.chunk;

import dev.miniclaudecode.rag.chunk.CodeChunk.Kind;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class JavaAstChunkerTest {
  @Test
  void extractsJavaSymbolsWithOwnersAndExactLines() {
    String source =
        "package example.orders;\n\n"
            + "class OrderService {\n"
            + "  private final String name = \"orders\";\n\n"
            + "  OrderService() {}\n\n"
            + "  String find(int id) {\n"
            + "    return name + id;\n"
            + "  }\n"
            + "}\n";
    List<CodeChunk> chunks = new JavaAstChunker().chunk("src/OrderService.java", source);
    Assertions.assertThat(chunks)
        .extracting(CodeChunk::kind)
        .containsExactly(new Kind[] {Kind.TYPE, Kind.FIELD, Kind.CONSTRUCTOR, Kind.METHOD});
    Assertions.assertThat(chunks)
        .allSatisfy(
            chunk -> Assertions.assertThat(chunk.packageName()).isEqualTo("example.orders"));
    Assertions.assertThat(chunks)
        .allSatisfy(chunk -> Assertions.assertThat(chunk.owner()).isEqualTo("OrderService"));
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.kind() == Kind.METHOD)
        .singleElement()
        .satisfies(
            chunk -> {
              Assertions.assertThat(chunk.symbol()).isEqualTo("find(int)");
              Assertions.assertThat(chunk.startLine()).isEqualTo(8);
              Assertions.assertThat(chunk.endLine()).isEqualTo(10);
              Assertions.assertThat(chunk.content()).startsWith("  String find(int id)");
            });
  }

  @Test
  void fallbackUsesTextWindowsForBrokenJava() {
    List<CodeChunk> chunks =
        new FallbackChunker().chunk("Broken.java", "class Broken {\n  void missing(\n");
    Assertions.assertThat(chunks).singleElement();
    Assertions.assertThat(chunks.getFirst().kind()).isEqualTo(Kind.TEXT);
    Assertions.assertThat(chunks.getFirst().language()).isEqualTo("java");
  }
}
