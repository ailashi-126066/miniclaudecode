package dev.miniclaudecode.rag.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.miniclaudecode.rag.chunk.CodeChunk;
import dev.miniclaudecode.rag.chunk.DocumentChunker;
import dev.miniclaudecode.rag.chunk.FallbackChunker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;

public final class LuceneCodeIndex {
  public static final String FIELD_DOCUMENT_TYPE = "document_type";
  public static final String FIELD_CHUNK_ID = "chunk_id";
  public static final String FIELD_PATH = "path";
  public static final String FIELD_LANGUAGE = "language";
  public static final String FIELD_KIND = "kind";
  public static final String FIELD_PACKAGE = "package";
  public static final String FIELD_OWNER = "owner";
  public static final String FIELD_SYMBOL = "symbol";
  public static final String FIELD_START_LINE = "start_line";
  public static final String FIELD_END_LINE = "end_line";
  public static final String FIELD_CONTENT = "content";
  public static final String FIELD_SEARCH_TEXT = "search_text";
  public static final String FIELD_PATH_TEXT = "path_text";
  public static final String FIELD_SYMBOL_TEXT = "symbol_text";
  public static final String FIELD_VECTOR = "vector";
  private static final String TYPE_CHUNK = "chunk";
  private final Path indexRoot;
  private final EmbeddingModel embeddingModel;
  private final WorkspaceScanner scanner;
  private final DocumentChunker chunker;
  private final FileFingerprintStore fingerprintStore;

  public LuceneCodeIndex(Path indexRoot, EmbeddingModel embeddingModel) {
    this(indexRoot, embeddingModel, new WorkspaceScanner(), new FallbackChunker());
  }

  public LuceneCodeIndex(
      Path indexRoot,
      EmbeddingModel embeddingModel,
      WorkspaceScanner scanner,
      DocumentChunker chunker) {
    this.indexRoot = indexRoot.toAbsolutePath().normalize();
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
    this.scanner = Objects.requireNonNull(scanner, "scanner must not be null");
    this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
    this.fingerprintStore =
        new FileFingerprintStore(this.indexRoot.resolve("fingerprints.properties"));
  }

  public LuceneCodeIndex.UpdateReport synchronize(Path workspace) throws IOException {
    Path lucenePath = this.indexRoot.resolve("lucene");
    Files.createDirectories(lucenePath);

    // The fingerprints must be loaded before the scan because the scanner uses them to skip
    // reading unchanged files — and they must be discarded when no Lucene index exists, so a
    // deleted index directory can never be masked by a surviving fingerprint file.
    Map<String, FileFingerprintStore.FileFingerprint> previous;
    try (Directory probe = FSDirectory.open(lucenePath)) {
      previous = DirectoryReader.indexExists(probe) ? this.fingerprintStore.load() : Map.of();
    }

    List<WorkspaceScanner.ScannedFile> scanned = this.scanner.scan(workspace, previous);
    Map<String, FileFingerprintStore.FileFingerprint> current = new HashMap<>();
    scanned.forEach(
        file ->
            current.put(
                file.path(),
                new FileFingerprintStore.FileFingerprint(
                    file.fingerprint(), file.sizeBytes(), file.modifiedMillis())));
    int unchanged = 0;
    int updated = 0;
    int chunks = 0;
    Set<String> removed = new HashSet<>(previous.keySet());
    removed.removeAll(current.keySet());

    try (Directory directory = FSDirectory.open(lucenePath)) {
      IndexWriter writer =
          new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
      boolean completed = false;

      try {
        for (WorkspaceScanner.ScannedFile file : scanned) {
          FileFingerprintStore.FileFingerprint before = previous.get(file.path());
          if (before != null && file.fingerprint().equals(before.contentHash())) {
            unchanged++;
          } else {
            // A changed file always carries content: the scanner only omits it when the cheap
            // signal matched an entry of `previous`, and that same entry makes the branch above
            // take the unchanged path. Failing loudly here beats silently skipping a re-chunk.
            String content =
                file.content()
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "scanner returned a changed file without content: " + file.path()));
            List<Document> documents = this.documents(this.chunker.chunk(file.path(), content));
            writer.updateDocuments(new Term("path", file.path()), documents);
            updated++;
            chunks += documents.size();
          }
        }

        for (String path : removed) {
          writer.deleteDocuments(new Term[] {new Term("path", path)});
        }

        writer.commit();
        completed = true;
      } finally {
        if (completed) {
          writer.close();
        } else {
          writer.rollback();
        }
      }
    }

    this.fingerprintStore.save(current);
    return new LuceneCodeIndex.UpdateReport(
        scanned.size(), updated, unchanged, removed.size(), chunks);
  }

  public List<CodeChunk> chunks() throws IOException {
    Path lucene = this.indexRoot.resolve("lucene");
    if (!Files.isDirectory(lucene)) {
      return List.of();
    }

    try (Directory directory = FSDirectory.open(lucene)) {
      if (!DirectoryReader.indexExists(directory)) {
        return List.of();
      }

      try (DirectoryReader reader = DirectoryReader.open(directory)) {
        List<CodeChunk> chunks = new ArrayList<>();

        for (LeafReaderContext leaf : reader.leaves()) {
          Bits liveDocuments = leaf.reader().getLiveDocs();

          for (int index = 0; index < leaf.reader().maxDoc(); index++) {
            if (liveDocuments == null || liveDocuments.get(index)) {
              Document document = leaf.reader().storedFields().document(index);
              if ("chunk".equals(document.get("document_type"))) {
                chunks.add(fromDocument(document));
              }
            }
          }
        }

        return List.copyOf(chunks);
      }
    }
  }

  public LuceneCodeIndex.IndexStats stats() throws IOException {
    List<CodeChunk> values = this.chunks();
    return new LuceneCodeIndex.IndexStats(
        values.stream().map(CodeChunk::path).distinct().count(),
        (long) values.size(),
        this.embeddingModel.dimension());
  }

  public Path luceneDirectory() {
    return this.indexRoot.resolve("lucene");
  }

  private List<Document> documents(List<CodeChunk> chunks) {
    List<Document> documents = new ArrayList<>(chunks.size());

    for (CodeChunk chunk : chunks) {
      float[] vector =
          (float[])
              ((Embedding) this.embeddingModel.embed(chunk.embeddingText()).content())
                  .vector()
                  .clone();
      if (vector.length == 0) {
        throw new IllegalStateException("embedding model returned an empty vector");
      }

      normalize(vector);
      Document document = new Document();
      document.add(new StringField("document_type", "chunk", Store.YES));
      document.add(new StringField("chunk_id", chunk.id(), Store.YES));
      document.add(new StringField("path", chunk.path(), Store.YES));
      document.add(new StringField("language", chunk.language(), Store.YES));
      document.add(new StringField("kind", chunk.kind().name(), Store.YES));
      document.add(new StringField("package", chunk.packageName(), Store.YES));
      document.add(new StringField("owner", chunk.owner(), Store.YES));
      document.add(new TextField("symbol", chunk.symbol(), Store.YES));
      document.add(new TextField("path_text", chunk.path(), Store.NO));
      document.add(new TextField("symbol_text", chunk.symbol(), Store.NO));
      document.add(new IntPoint("start_line", new int[] {chunk.startLine()}));
      document.add(new StoredField("start_line", chunk.startLine()));
      document.add(new IntPoint("end_line", new int[] {chunk.endLine()}));
      document.add(new StoredField("end_line", chunk.endLine()));
      document.add(new StoredField("content", chunk.content()));
      document.add(new TextField("search_text", chunk.lexicalText(), Store.NO));
      document.add(new KnnFloatVectorField("vector", vector, VectorSimilarityFunction.DOT_PRODUCT));
      documents.add(document);
    }

    return documents;
  }

  private static CodeChunk fromDocument(Document document) {
    return new CodeChunk(
        document.get("chunk_id"),
        document.get("path"),
        document.get("language"),
        CodeChunk.Kind.valueOf(document.get("kind")),
        document.get("package"),
        document.get("owner"),
        document.get("symbol"),
        document.getField("start_line").numericValue().intValue(),
        document.getField("end_line").numericValue().intValue(),
        document.get("content"));
  }

  public static CodeChunk storedChunk(Document document) {
    Objects.requireNonNull(document, "document must not be null");
    return fromDocument(document);
  }

  private static void normalize(float[] vector) {
    double sum = 0.0;

    for (float value : vector) {
      sum += (double) (value * value);
    }

    if (sum == 0.0 && vector.length > 0) {
      vector[0] = 1.0F;
    } else {
      double magnitude = Math.sqrt(sum);

      for (int index = 0; index < vector.length; index++) {
        vector[index] = (float) ((double) vector[index] / magnitude);
      }
    }
  }

  public static record IndexStats(long files, long chunks, int vectorDimensions) {}

  public static record UpdateReport(
      int files, int updatedFiles, int unchangedFiles, int deletedFiles, int writtenChunks) {}
}
