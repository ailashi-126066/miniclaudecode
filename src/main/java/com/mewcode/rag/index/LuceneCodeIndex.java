package com.mewcode.rag.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import com.mewcode.rag.chunk.CodeChunk;
import com.mewcode.rag.chunk.DocumentChunker;
import com.mewcode.rag.chunk.FallbackChunker;
import com.mewcode.rag.embedding.BatchEmbeddingModel;
import com.mewcode.rag.embedding.EmbeddingIdentity;
import com.mewcode.rag.search.HybridCodeSearcher;
import com.mewcode.rag.search.SearchResult;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;

public final class LuceneCodeIndex {
  private static final ConcurrentHashMap<Path, ReentrantLock> SYNC_LOCKS =
      new ConcurrentHashMap<>();

  /**
   * Bumped whenever indexed terms change meaning: the analyzer, the chunkers, or the text fed into
   * {@code search_text}. It is recorded alongside the embedding identity and a mismatch forces a
   * full rebuild, because terms written by an older pipeline are not comparable with queries
   * analyzed by the current one — and the failure mode is silent (zero hits), not loud.
   *
   * <p>v3: Tree-sitter declaration boundaries for non-Java source files.
   */
  public static final String SCHEMA_VERSION = "v3";

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
  public static final String FIELD_PARENT_CHUNK_ID = "parent_chunk_id";
  public static final String FIELD_CHUNK_ROLE = "chunk_role";
  public static final String FIELD_CHILD_INDEX = "child_index";
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
  private final WorkspaceVersion workspaceVersion;

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
    this.workspaceVersion = new WorkspaceVersion(this.indexRoot);
  }

  public LuceneCodeIndex.UpdateReport synchronize(Path workspace) throws IOException {
    // One synchronize at a time per index root: the in-process lock serializes threads (two
    // LuceneCodeIndex instances over the same root would otherwise race the IndexWriter lock and
    // deleteTree), the file lock serializes processes (`index` CLI racing a REPL code_search).
    ReentrantLock processLock =
        SYNC_LOCKS.computeIfAbsent(this.indexRoot, ignored -> new ReentrantLock());
    processLock.lock();
    try {
      Files.createDirectories(this.indexRoot);
      try (FileChannel lockChannel =
              FileChannel.open(
                  this.indexRoot.resolve("sync.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          FileLock ignored = lockChannel.lock()) {
        UpdateReport report = this.synchronizeLocked(workspace);
        this.workspaceVersion.save(workspace);
        return report;
      }
    } finally {
      processLock.unlock();
    }
  }

  private LuceneCodeIndex.UpdateReport synchronizeLocked(Path workspace) throws IOException {
    Path lucenePath = this.indexRoot.resolve("lucene");
    Files.createDirectories(lucenePath);
    boolean indexExists;
    try (Directory probe = FSDirectory.open(lucenePath)) {
      indexExists = DirectoryReader.indexExists(probe);
    }

    // Vectors from a different embedding model or dimension are not comparable, and Lucene
    // rejects mixed dimensions on one field. When the recorded identity differs from the current
    // model — or is missing, which means the vectors have unknown provenance — drop the whole
    // index so the scan below rebuilds it from scratch. A silent partial mix would corrupt every
    // subsequent vector search. The schema version rides along for the same reason on the lexical
    // side: old terms plus a new analyzer produce no hits rather than an error.
    String identity = this.indexIdentity();
    if (indexExists && !this.recordedEmbeddingIdentity().equals(identity)) {
      this.probeEmbeddingBackendBeforeDestroying();
      deleteTree(lucenePath);
      Files.createDirectories(lucenePath);
      indexExists = false;
    }

    // The fingerprints must be loaded before the scan because the scanner uses them to skip
    // reading unchanged files — and they must be discarded when no Lucene index exists, so a
    // deleted index directory can never be masked by a surviving fingerprint file.
    Map<String, FileFingerprintStore.FileFingerprint> previous =
        indexExists ? this.fingerprintStore.load() : Map.of();

    // Deletions are detected as `previous minus current`, so an index without fingerprints (lost
    // file, schema-version bump) has no way to honor deletions incrementally: documents of files
    // deleted while the fingerprints were absent would linger forever. No incremental knowledge
    // means the only correct move is a full rebuild.
    if (indexExists && previous.isEmpty()) {
      this.probeEmbeddingBackendBeforeDestroying();
      deleteTree(lucenePath);
      Files.createDirectories(lucenePath);
      indexExists = false;
    }

    // Persisted BEFORE any vector is written: a crash mid-build then leaves either an empty index
    // tagged with the current identity (rebuilt consistently on the next run) or the old index
    // untouched — never committed vectors tagged with a stale identity, which the gate above
    // would wrongly accept and no later run could repair.
    Files.writeString(this.indexRoot.resolve("embedding.id"), identity);

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
          new IndexWriter(directory, new IndexWriterConfig(new CodeSearchAnalyzer()));
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

  /**
   * Proves the embedding backend answers before the old index is destroyed. Without this, the path
   * most likely to fail — first synchronize after switching to a remote provider whose endpoint is
   * down — would delete the BM25 fields (which never needed embeddings) along with the vectors and
   * then fail the rebuild, leaving no searchable index at all.
   */
  private void probeEmbeddingBackendBeforeDestroying() {
    this.embeddingModel.embed("embedding backend probe");
  }

  /** What the on-disk index was built by: lexical schema plus embedding model. */
  private String indexIdentity() {
    return SCHEMA_VERSION + "|" + this.embeddingIdentity();
  }

  private String embeddingIdentity() {
    // Fallback uses getName(): getSimpleName() is empty for anonymous classes, which would give
    // two different anonymous models the same identity "/N".
    return this.embeddingModel instanceof EmbeddingIdentity identified
        ? identified.embeddingIdentity()
        : this.embeddingModel.getClass().getName() + "/" + this.embeddingModel.dimension();
  }

  private String recordedEmbeddingIdentity() throws IOException {
    Path identityFile = this.indexRoot.resolve("embedding.id");
    return Files.exists(identityFile) ? Files.readString(identityFile).trim() : "";
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
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

  /** Replaces matched child snippets with their bounded parent section before model rendering. */
  public HybridCodeSearcher.SearchResponse hydrateParentContext(
      HybridCodeSearcher.SearchResponse response, int tokenBudget) throws IOException {
    Objects.requireNonNull(response, "response must not be null");
    Path lucene = this.indexRoot.resolve("lucene");
    if (!Files.isDirectory(lucene) || response.results().isEmpty()) {
      return response;
    }
    try (Directory directory = FSDirectory.open(lucene)) {
      if (!DirectoryReader.indexExists(directory)) {
        return response;
      }
      try (DirectoryReader reader = DirectoryReader.open(directory)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        Map<String, CodeChunk> parents = new HashMap<>();
        Map<String, SearchResult> hydrated = new java.util.LinkedHashMap<>();
        for (SearchResult result : response.results()) {
          CodeChunk child = result.chunk();
          if (child.parentChunkId().isBlank()) {
            hydrated.putIfAbsent(child.id(), result);
            continue;
          }
          CodeChunk parent = parents.get(child.parentChunkId());
          if (parent == null) {
            org.apache.lucene.search.TopDocs found =
                searcher.search(new TermQuery(new Term(FIELD_CHUNK_ID, child.parentChunkId())), 1);
            if (found.scoreDocs.length == 1) {
              parent = fromDocument(reader.storedFields().document(found.scoreDocs[0].doc));
              parents.put(parent.id(), parent);
            }
          }
          CodeChunk context = parent == null ? child : parent;
          hydrated.putIfAbsent(
              context.id(),
              new SearchResult(context, result.fusedScore(), result.ranks(), result.rawScores()));
        }
        return response.withContext(List.copyOf(hydrated.values()), tokenBudget);
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

  public String workspaceVersion() throws IOException {
    return this.workspaceVersion.read();
  }

  public Path luceneDirectory() {
    return this.indexRoot.resolve("lucene");
  }

  private List<Document> documents(List<CodeChunk> chunks) {
    List<Document> documents = new ArrayList<>(chunks.size());
    List<CodeChunk> vectorChunks =
        chunks.stream().filter(chunk -> chunk.role() != CodeChunk.Role.PARENT).toList();
    List<Embedding> embeddings =
        vectorChunks.isEmpty() ? List.of() : this.embeddingsFor(vectorChunks);
    if (embeddings.size() != vectorChunks.size()) {
      throw new IllegalStateException("embedding model returned an unexpected number of vectors");
    }
    int vectorIndex = 0;

    for (CodeChunk chunk : chunks) {
      Document document = new Document();
      document.add(
          new StringField(
              "document_type",
              chunk.role() == CodeChunk.Role.PARENT ? "parent" : "chunk",
              Store.YES));
      document.add(new StringField("chunk_id", chunk.id(), Store.YES));
      document.add(new StringField("parent_chunk_id", chunk.parentChunkId(), Store.YES));
      document.add(new StringField("chunk_role", chunk.role().name(), Store.YES));
      document.add(new StoredField("child_index", chunk.childIndex()));
      document.add(new StringField("path", chunk.path(), Store.YES));
      document.add(new StringField("language", chunk.language(), Store.YES));
      document.add(new StringField("kind", chunk.kind().name(), Store.YES));
      document.add(new StringField("package", chunk.packageName(), Store.YES));
      document.add(new StringField("owner", chunk.owner(), Store.YES));
      document.add(new TextField("symbol", chunk.symbol(), Store.YES));
      document.add(new IntPoint("start_line", new int[] {chunk.startLine()}));
      document.add(new StoredField("start_line", chunk.startLine()));
      document.add(new IntPoint("end_line", new int[] {chunk.endLine()}));
      document.add(new StoredField("end_line", chunk.endLine()));
      document.add(new StoredField("content", chunk.content()));
      if (chunk.role() != CodeChunk.Role.PARENT) {
        document.add(new TextField("path_text", chunk.path(), Store.NO));
        document.add(new TextField("symbol_text", chunk.symbol(), Store.NO));
        float[] vector = embeddings.get(vectorIndex++).vector().clone();
        if (vector.length == 0) {
          throw new IllegalStateException("embedding model returned an empty vector");
        }
        normalize(vector);
        document.add(new TextField("search_text", chunk.lexicalText(), Store.NO));
        document.add(
            new KnnFloatVectorField("vector", vector, VectorSimilarityFunction.DOT_PRODUCT));
      }
      documents.add(document);
    }

    return documents;
  }

  private List<Embedding> embeddingsFor(List<CodeChunk> chunks) {
    List<TextSegment> segments =
        chunks.stream().map(chunk -> TextSegment.from(chunk.embeddingText())).toList();
    if (this.embeddingModel instanceof BatchEmbeddingModel batchModel) {
      return batchModel.embedAll(segments).content();
    }
    return chunks.stream()
        .map(chunk -> this.embeddingModel.embed(chunk.embeddingText()).content())
        .toList();
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
        document.get("content"),
        document.get("parent_chunk_id"),
        CodeChunk.Role.valueOf(document.get("chunk_role")),
        document.getField("child_index").numericValue().intValue());
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
