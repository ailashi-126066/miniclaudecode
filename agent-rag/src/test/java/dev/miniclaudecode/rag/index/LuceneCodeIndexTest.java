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
  void lostFingerprintsForceAFullRebuildSoDeletionsAreStillHonored() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace-fp-loss"));
    Files.writeString(workspace.resolve("Keep.java"), "class Keep { void keep() {} }\n");
    Files.writeString(workspace.resolve("Gone.java"), "class Gone { void gone() {} }\n");
    Path indexPath = this.temporaryDirectory.resolve("fp-loss-index");
    LuceneCodeIndex index = new LuceneCodeIndex(indexPath, new FakeEmbeddingModel());
    index.synchronize(workspace);

    // Simulate losing the incremental knowledge (schema bump, corrupt/lost fingerprints) while
    // the Lucene index survives, AND deleting a workspace file in the same window. Incremental
    // deletion detection is impossible without fingerprints, so a full rebuild must purge the
    // stale documents instead of letting them linger forever.
    Files.delete(workspace.resolve("Gone.java"));
    Files.delete(indexPath.resolve("fingerprints.properties.version"));
    index.synchronize(workspace);
    Assertions.assertThat(index.chunks())
        .extracting(CodeChunk::path)
        .containsOnly(new String[] {"Keep.java"});
  }

  @Test
  void missingIdentitySidecarTreatsVectorsAsUnknownAndRebuilds() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace-noid"));
    Files.writeString(workspace.resolve("App.java"), "class App { void a() {} }\n");
    Path indexPath = this.temporaryDirectory.resolve("noid-index");
    LuceneCodeIndex index = new LuceneCodeIndex(indexPath, new FakeEmbeddingModel());
    index.synchronize(workspace);

    // An index whose identity sidecar is gone has vectors of unknown provenance (crash window,
    // deleted file). Accepting them on faith is how mixed vector spaces sneak in.
    Files.delete(indexPath.resolve("embedding.id"));
    UpdateReport rebuilt = index.synchronize(workspace);
    Assertions.assertThat(rebuilt.updatedFiles()).isEqualTo(1);
    Assertions.assertThat(rebuilt.unchangedFiles()).isZero();
    Assertions.assertThat(index.synchronize(workspace).unchangedFiles()).isEqualTo(1);
  }

  @Test
  void identitySwitchToAFailingBackendLeavesTheOldIndexReadable() throws Exception {
    Path workspace = Files.createDirectory(this.temporaryDirectory.resolve("workspace-probe"));
    Files.writeString(workspace.resolve("App.java"), "class App { void a() {} }\n");
    Path indexPath = this.temporaryDirectory.resolve("probe-index");
    LuceneCodeIndex healthy = new LuceneCodeIndex(indexPath, new FakeEmbeddingModel());
    healthy.synchronize(workspace);

    // Switching identity used to delete the old index before the first embed call, so a dead
    // remote endpoint destroyed BM25 search too. The probe must fail BEFORE anything is deleted.
    EmbeddingModel dead =
        new EmbeddingModel() {
          public Response<Embedding> embed(String text) {
            throw new IllegalStateException("endpoint unreachable");
          }

          public int dimension() {
            return 4;
          }
        };
    Assertions.assertThatThrownBy(() -> new LuceneCodeIndex(indexPath, dead).synchronize(workspace))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint unreachable");
    Assertions.assertThat(new LuceneCodeIndex(indexPath, new FakeEmbeddingModel()).chunks())
        .extracting(CodeChunk::symbol)
        .contains(new String[] {"a()"});
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
