package dev.miniclaudecode.rag.chunk;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LangChainDocumentChunkerTest {
  @Test
  void recursivelySplitsExtractedDocumentsAndRetainsSourceMetadata() {
    String paragraph = "The payment workflow validates an order before it sends a receipt. ";
    String content = "# Billing\n" + paragraph.repeat(30) + "\n# Refunds\n" + paragraph.repeat(30);

    var chunks = new LangChainDocumentChunker(80, 10).chunk("docs/payments.html", content);

    Assertions.assertThat(chunks).hasSizeGreaterThan(2);
    Assertions.assertThat(chunks)
        .allSatisfy(
            chunk -> {
              Assertions.assertThat(chunk.path()).isEqualTo("docs/payments.html");
              Assertions.assertThat(chunk.startLine()).isGreaterThanOrEqualTo(1);
              Assertions.assertThat(chunk.endLine()).isGreaterThanOrEqualTo(chunk.startLine());
              Assertions.assertThat(chunk.content()).isNotBlank();
            });
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.role() == CodeChunk.Role.PARENT)
        .extracting(CodeChunk::symbol)
        .contains("# Billing", "# Refunds");
    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.role() == CodeChunk.Role.CHILD)
        .allSatisfy(child -> Assertions.assertThat(child.parentChunkId()).isNotBlank());
  }

  @Test
  void ignoresControlOnlyContentThatLangChainTreatsAsBlank() {
    var chunks = new LangChainDocumentChunker().chunk("guide.md", "\u0001");

    Assertions.assertThat(chunks).isEmpty();
  }
}
