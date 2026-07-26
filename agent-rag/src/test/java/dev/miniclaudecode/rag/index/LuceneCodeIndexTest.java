package dev.miniclaudecode.rag.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.miniclaudecode.rag.FakeEmbeddingModel;
import dev.miniclaudecode.rag.chunk.CodeChunk;
import dev.miniclaudecode.rag.index.LuceneCodeIndex.UpdateReport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneCodeIndexTest {
  @TempDir Path temporaryDirectory;

  @Test
  void incrementallyUpdatesChangedFilesAndSynchronizesDeletes() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace"));
    Path javaFile = workspace.resolve("App.java");
    Path readme = workspace.resolve("README.md");
    Files.writeString(javaFile, "class App { void first() {} }\n");
    Files.writeString(readme, "# Guide\nhello\n");
    LuceneCodeIndex index =
        new LuceneCodeIndex(this.temporaryDirectory.resolve("index"), new FakeEmbeddingModel());
    UpdateReport first = index.synchronize(workspace);
    UpdateReport second = index.synchronize(workspace);
    Assertions.assertThat(first.updatedFiles()).isEqualTo(2);
    Assertions.assertThat(second.updatedFiles()).isZero();
    Assertions.assertThat(second.unchangedFiles()).isEqualTo(2);
    Assertions.assertThat(index.chunks())
        .extracting(CodeChunk::path)
        .contains(new String[] {"App.java", "README.md"});
    Assertions.assertThat(index.stats().vectorDimensions()).isEqualTo(8);
    Files.writeString(javaFile, "class App { void second() {} }\n");
    Files.delete(readme);
    UpdateReport third = index.synchronize(workspace);
    Assertions.assertThat(third.updatedFiles()).isEqualTo(1);
    Assertions.assertThat(third.deletedFiles()).isEqualTo(1);
    Assertions.assertThat(index.chunks())
        .extracting(CodeChunk::path)
        .containsOnly(new String[] {"App.java"});
    Assertions.assertThat(index.chunks())
        .extracting(CodeChunk::symbol)
        .contains(new String[] {"second()"});
  }

  @Test
  void changingTheEmbeddingIdentityRebuildsTheWholeIndex() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace-identity"));
    Files.writeString(workspace.resolve("App.java"), "class App { void first() {} }\n");
    Path indexPath = this.temporaryDirectory.resolve("identity-index");
    LuceneCodeIndex first = new LuceneCodeIndex(indexPath, new FakeEmbeddingModel());
    first.synchronize(workspace);
    Assertions.assertThat(first.synchronize(workspace).unchangedFiles()).isEqualTo(1);

    // Same index root, different vector space. Lucene rejects mixed dimensions on one field, so
    // the index must rebuild from zero instead of appending 16-dim vectors next to 8-dim ones.
    EmbeddingModel other =
        new EmbeddingModel() {
          public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(new float[] {1.0F, 0.0F, 0.0F, 0.0F}));
          }

          public int dimension() {
            return 4;
          }
        };
    LuceneCodeIndex switched = new LuceneCodeIndex(indexPath, other);
    UpdateReport rebuilt = switched.synchronize(workspace);
    Assertions.assertThat(rebuilt.updatedFiles()).isEqualTo(1);
    Assertions.assertThat(rebuilt.unchangedFiles()).isZero();
    Assertions.assertThat(switched.stats().vectorDimensions()).isEqualTo(4);
    Assertions.assertThat(switched.synchronize(workspace).unchangedFiles()).isEqualTo(1);
  }

  @Test
  void failedEmbeddingBatchLeavesLastCommittedSnapshotReadable() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace-failure"));
    Path source = workspace.resolve("Stable.java");
    Files.writeString(source, "class Stable { void good() {} }\n");
    Path indexPath = this.temporaryDirectory.resolve("failure-index");
    EmbeddingModel conditional =
        new EmbeddingModel() {
          public Response<Embedding> embed(String text) {
            if (text.contains("explode")) {
              throw new IllegalStateException("embedding unavailable");
            } else {
              return new FakeEmbeddingModel().embed(text);
            }
          }

          public int dimension() {
            return 8;
          }
        };
    LuceneCodeIndex index = new LuceneCodeIndex(indexPath, conditional);
    index.synchronize(workspace);
    Files.writeString(source, "class Stable { void explode() {} }\n");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> index.synchronize(workspace))
                .isInstanceOf(IllegalStateException.class))
        .hasMessageContaining("embedding unavailable");
    ((AbstractListAssert)
            Assertions.assertThat(new LuceneCodeIndex(indexPath, conditional).chunks())
                .extracting(CodeChunk::symbol)
                .contains(new String[] {"good()"}))
        .doesNotContain(new String[] {"explode()"});
  }
}
