package dev.miniclaudecode.rag.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.rag.chunk.CodeChunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeAwareRerankerTest {
  private final CodeAwareReranker reranker = new CodeAwareReranker();

  @Test
  void exactSymbolMatchOutranksIncidentalContentMention() {
    SearchResult incidental =
        result(
            "src/other/Helper.java",
            "formatOutput",
            "// see parseRequest for details; parseRequest is documented elsewhere",
            0.9);
    SearchResult exactSymbol =
        result(
            "src/http/RequestParser.java",
            "parseRequest",
            "public Request parseRequest(String raw) { return new Request(raw); }",
            0.1);

    List<SearchResult> ordered =
        this.reranker.rerank("parseRequest", List.of(incidental, exactSymbol));

    assertThat(ordered.get(0).chunk().symbol()).isEqualTo("parseRequest");
    assertThat(ordered).containsExactly(exactSymbol, incidental);
  }

  private static SearchResult result(
      String path, String symbol, String content, double fusedScore) {
    CodeChunk chunk =
        CodeChunk.create(
            path, "java", CodeChunk.Kind.METHOD, "dev.example", "Owner", symbol, 1, 5, content);
    return new SearchResult(chunk, fusedScore, Map.of(), Map.of());
  }
}
