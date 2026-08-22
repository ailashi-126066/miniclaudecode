package com.mewcode.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.rag.chunk.CodeChunk;
import com.mewcode.rag.embedding.LocalCodeEmbeddingModel;
import com.mewcode.rag.index.FileFingerprintStore;
import com.mewcode.rag.index.LuceneCodeIndex;
import com.mewcode.rag.index.WorkspaceScanner;
import com.mewcode.rag.search.Bm25Retriever;
import com.mewcode.rag.search.HybridCodeSearcher;
import com.mewcode.rag.search.VectorRetriever;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manual, workspace-private knowledge base backed by its own Lucene index. */
public final class KnowledgeRagService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path knowledgeRoot;
    private final Path indexRoot;
    private final LocalCodeEmbeddingModel embeddings = new LocalCodeEmbeddingModel();
    private final WorkspaceScanner scanner = WorkspaceScanner.standaloneDocuments();

    public KnowledgeRagService(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        this.knowledgeRoot = root.resolve(".mewcode").resolve("knowledge");
        this.indexRoot = root.resolve(".mewcode").resolve("knowledge-index");
    }

    public Path knowledgeRoot() { return knowledgeRoot; }
    public Path indexRoot() { return indexRoot; }

    public LuceneCodeIndex.UpdateReport synchronize() throws IOException {
        Files.createDirectories(knowledgeRoot);
        LuceneCodeIndex index = index();
        LuceneCodeIndex.UpdateReport report = index.synchronize(knowledgeRoot);
        writeCatalog(index.chunks());
        return report;
    }

    public Status status() throws IOException {
        if (!Files.isDirectory(knowledgeRoot)) {
            return new Status(false, false, 0, 0, knowledgeRoot, indexRoot);
        }
        Path lucene = indexRoot.resolve("lucene");
        if (!Files.isDirectory(lucene)) {
            return new Status(false, hasDocuments(), 0, 0, knowledgeRoot, indexRoot);
        }

        Map<String, FileFingerprintStore.FileFingerprint> previous =
                new FileFingerprintStore(indexRoot.resolve("fingerprints.properties")).load();
        List<WorkspaceScanner.ScannedFile> current = scanner.scan(knowledgeRoot, previous);
        Map<String, FileFingerprintStore.FileFingerprint> fingerprints = new LinkedHashMap<>();
        for (var file : current) {
            fingerprints.put(file.path(), new FileFingerprintStore.FileFingerprint(
                    file.fingerprint(), file.sizeBytes(), file.modifiedMillis()));
        }
        return new Status(true, !fingerprints.equals(previous), fingerprints.size(), index().stats().chunks(),
                knowledgeRoot, indexRoot);
    }

    public HybridCodeSearcher.SearchResponse search(String query) throws IOException {
        LuceneCodeIndex index = index();
        HybridCodeSearcher searcher = new HybridCodeSearcher(
                new Bm25Retriever(index.luceneDirectory()),
                new VectorRetriever(index.luceneDirectory(), embeddings));
        return index.hydrateParentContext(searcher.search(query), 4500);
    }

    /** Compact catalog shown to the model so it knows which knowledge can be retrieved. */
    public String catalogReminder() {
        Path catalog = indexRoot.resolve("catalog.json");
        if (!Files.isRegularFile(catalog)) return "";
        try {
            List<CatalogEntry> entries = JSON.readValue(
                    catalog.toFile(),
                    JSON.getTypeFactory().constructCollectionType(List.class, CatalogEntry.class));
            if (entries.isEmpty()) return "";
            StringBuilder out = new StringBuilder("# Available private knowledge\n");
            for (CatalogEntry entry : entries.stream().limit(20).toList()) {
                out.append("- ").append(entry.title()).append(" (").append(entry.path()).append(")");
                if (entry.summary() != null && !entry.summary().isBlank()) {
                    out.append(": ").append(entry.summary());
                }
                out.append('\n');
            }
            out.append("Use KnowledgeSearch when these documents are relevant. If the index may be stale, ask the user to run /knowledge index.");
            return out.toString();
        } catch (IOException ignored) {
            return "";
        }
    }

    private boolean hasDocuments() throws IOException {
        try (var files = Files.walk(knowledgeRoot)) {
            return files.anyMatch(Files::isRegularFile);
        }
    }

    private LuceneCodeIndex index() {
        return new LuceneCodeIndex(indexRoot, embeddings, scanner, new com.mewcode.rag.chunk.FallbackChunker());
    }

    private void writeCatalog(List<CodeChunk> chunks) throws IOException {
        Files.createDirectories(indexRoot);
        Map<String, CodeChunk> firstByPath = new LinkedHashMap<>();
        chunks.stream()
                .filter(chunk -> chunk.role() != CodeChunk.Role.PARENT)
                .sorted(Comparator.comparing(CodeChunk::path).thenComparingInt(CodeChunk::startLine))
                .forEach(chunk -> firstByPath.putIfAbsent(chunk.path(), chunk));
        List<CatalogEntry> entries = firstByPath.values().stream()
                .map(chunk -> new CatalogEntry(chunk.path(), title(chunk), summary(chunk.content())))
                .toList();
        JSON.writerWithDefaultPrettyPrinter().writeValue(indexRoot.resolve("catalog.json").toFile(), entries);
    }

    private static String title(CodeChunk chunk) {
        String symbol = chunk.symbol() == null ? "" : chunk.symbol().strip();
        if (!symbol.isBlank() && !"document".equalsIgnoreCase(symbol)) return symbol;
        for (String line : chunk.content().lines().toList()) {
            String value = line.replaceFirst("^#+\\s*", "").strip();
            if (!value.isBlank()) return value.length() > 120 ? value.substring(0, 120) : value;
        }
        return chunk.path();
    }

    private static String summary(String content) {
        String value = content == null ? "" : content.replaceAll("\\s+", " ").strip();
        return value.length() > 240 ? value.substring(0, 240) + "..." : value;
    }

    public record Status(boolean indexed, boolean stale, long documents, long chunks,
                         Path knowledgeRoot, Path indexRoot) {}
    public record CatalogEntry(String path, String title, String summary) {}
}
