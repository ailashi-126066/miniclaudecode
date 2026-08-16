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
  void everyMarkdownHeadingLevelOpensItsOwnSection() {
    // Only `# ` counted before, so a document whose H1 is the title and whose real structure is
    // `##`/`###` — most documents — became one region cut on token windows, and every chunk
    // inherited the title as its heading.
    String content =
        """
        # Guide

        Intro paragraph.

        ## Installation

        Install the tool first.

        ### Windows

        Use PowerShell.
        """;

    var chunks = new LangChainDocumentChunker(400, 20).chunk("docs/guide.md", content);

    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.role() == CodeChunk.Role.PARENT)
        .extracting(CodeChunk::symbol)
        .containsExactly("# Guide", "## Installation", "### Windows");
  }

  @Test
  void aHashWithoutASpaceIsNotAHeading() {
    var chunks =
        new LangChainDocumentChunker(400, 20)
            .chunk("notes.md", "# Real heading\n\n#hashtag not a heading\n\nbody text\n");

    Assertions.assertThat(chunks)
        .filteredOn(chunk -> chunk.role() == CodeChunk.Role.PARENT)
        .extracting(CodeChunk::symbol)
        .containsExactly("# Real heading");
  }

  @Test
  void ignoresControlOnlyContentThatLangChainTreatsAsBlank() {
    var chunks = new LangChainDocumentChunker().chunk("guide.md", "\u0001");

    Assertions.assertThat(chunks).isEmpty();
  }
}
