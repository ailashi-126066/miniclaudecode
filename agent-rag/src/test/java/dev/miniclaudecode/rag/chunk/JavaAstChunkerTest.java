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
  void typeChunkIsAStructuralSkeletonWithoutMethodBodies() {
    String source =
        "package example.orders;\n\n"
            + "/** Coordinates order lookups. */\n"
            + "public class OrderService extends Base implements Lookup {\n"
            + "  private final String name = \"orders\";\n\n"
            + "  private static final String QUERY =\n"
            + "      \"select order_id, customer_id, total from orders where region = ?\";\n\n"
            + "  OrderService() {}\n\n"
            + "  String find(int id) {\n"
            + "    return name + id;\n"
            + "  }\n\n"
            + "  static class Inner {\n"
            + "    void helper() {}\n"
            + "  }\n"
            + "}\n";
    List<CodeChunk> chunks = new JavaAstChunker().chunk("src/OrderService.java", source);
    CodeChunk outer =
        chunks.stream()
            .filter(chunk -> chunk.kind() == Kind.TYPE && chunk.symbol().equals("OrderService"))
            .findFirst()
            .orElseThrow();
    Assertions.assertThat(outer.content())
        .contains("package example.orders;")
        .contains("Coordinates order lookups.")
        .contains("public class OrderService extends Base implements Lookup {")
        .contains("private final String name = \"orders\";")
        .contains("OrderService();")
        .contains("String find(int id);")
        .contains("static class Inner { ... }")
        .doesNotContain("return name + id;")
        .doesNotContain("void helper() {}");
    // Large initializers are body, not structure: elided from the skeleton.
    Assertions.assertThat(outer.content()).contains("QUERY = ...;");
    // The skeleton replaces the content only; the chunk still spans the real file region and the
    // member bodies still live in their own chunks.
    Assertions.assertThat(outer.startLine()).isEqualTo(4);
    Assertions.assertThat(outer.endLine()).isEqualTo(19);
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.kind() == Kind.METHOD && chunk.symbol().equals("find(int)"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.content()).contains("return name + id;"));
  }

  @Test
  void initializersCompactConstructorsAndAnnotationMembersStaySearchable() {
    String source =
        "package example;\n\n"
            + "class Plugins {\n"
            + "  static {\n"
            + "    Registry.install(new AlphaPlugin());\n"
            + "  }\n\n"
            + "  {\n"
            + "    instanceCounter++;\n"
            + "  }\n"
            + "}\n\n"
            + "record Wrapper(String value) {\n"
            + "  public Wrapper {\n"
            + "    value = value.trim();\n"
            + "  }\n"
            + "}\n\n"
            + "@interface Marker {\n"
            + "  String reason() default \"unspecified\";\n"
            + "}\n";
    List<CodeChunk> chunks = new JavaAstChunker().chunk("src/Plugins.java", source);
    // The bodies must live in their own chunks now that TYPE chunks are skeletons.
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("static initializer"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.content()).contains("Registry.install"));
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.symbol().equals("instance initializer"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.content()).contains("instanceCounter++"));
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.kind() == Kind.CONSTRUCTOR && chunk.owner().equals("Wrapper"))
        .singleElement()
        .satisfies(chunk -> Assertions.assertThat(chunk.content()).contains("value.trim()"));
    // Skeletons keep the structural placeholders and annotation member declarations.
    Assertions.assertThat(skeletonOf(chunks, "Plugins")).contains("static { ... }");
    Assertions.assertThat(skeletonOf(chunks, "Marker"))
        .contains("String reason() default \"unspecified\";");
  }

  private static String skeletonOf(List<CodeChunk> chunks, String symbol) {
    return chunks.stream()
        .filter(chunk -> chunk.kind() == Kind.TYPE && chunk.symbol().equals(symbol))
        .findFirst()
        .orElseThrow()
        .content();
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
