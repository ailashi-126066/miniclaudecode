package com.mewcode.rag.index;

import com.mewcode.rag.parse.DocumentTextExtractor;
import com.mewcode.rag.parse.MultiFormatDocumentExtractor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class WorkspaceScanner {
  private static final Set<String> IGNORED_DIRECTORIES =
      Set.of(
          ".git",
          ".idea",
          ".gradle",
          ".mvn",
          ".mini-claude-code",
          ".mewcode",
          "benchmarks",
          "target",
          "build",
          "out",
          "node_modules");
  private static final Set<String> BINARY_EXTENSIONS =
      Set.of(
          "class", "jar", "war", "zip", "gz", "png", "jpg", "jpeg", "gif", "ico", "exe", "dll",
          "so", "dylib", "woff", "woff2", "ttf", "mp3", "mp4");
  private static final long DEFAULT_MAXIMUM_FILE_BYTES = 2L * 1024 * 1024;

  /**
   * Documents get a far larger budget than source files.
   *
   * <p>One 2 MiB limit governed both, which is generous for a source file and small for a real PDF
   * or spreadsheet — so the format support in {@link MultiFormatDocumentExtractor} mostly never ran
   * on actual documents, and the skip was silent. The asymmetry is the point: a 2 MiB source file
   * is machine-generated and worth skipping, while a 20 MiB PDF is exactly the manual someone
   * wanted indexed. Extraction reduces it to text before chunking, so the index cost tracks the
   * prose, not the file size.
   */
  private static final long DEFAULT_MAXIMUM_DOCUMENT_BYTES = 32L * 1024 * 1024;

  private final long maximumFileBytes;
  private final long maximumDocumentBytes;
  private final DocumentTextExtractor documentExtractor;
  private final boolean useGitCandidates;
  private final Set<String> ignoredDirectories;

  public WorkspaceScanner() {
    this(DEFAULT_MAXIMUM_FILE_BYTES);
  }

  public WorkspaceScanner(long maximumFileBytes) {
    this(maximumFileBytes, new MultiFormatDocumentExtractor());
  }

  public WorkspaceScanner(long maximumFileBytes, DocumentTextExtractor documentExtractor) {
    this(
        maximumFileBytes,
        Math.max(maximumFileBytes, DEFAULT_MAXIMUM_DOCUMENT_BYTES),
        documentExtractor);
  }

  public WorkspaceScanner(
      long maximumFileBytes, long maximumDocumentBytes, DocumentTextExtractor documentExtractor) {
    this(
        maximumFileBytes,
        maximumDocumentBytes,
        documentExtractor,
        true,
        IGNORED_DIRECTORIES);
  }

  private WorkspaceScanner(
      long maximumFileBytes,
      long maximumDocumentBytes,
      DocumentTextExtractor documentExtractor,
      boolean useGitCandidates,
      Set<String> ignoredDirectories) {
    if (maximumFileBytes < 1L || maximumDocumentBytes < maximumFileBytes) {
      throw new IllegalArgumentException("invalid scanner size limits");
    }
    this.maximumFileBytes = maximumFileBytes;
    this.maximumDocumentBytes = maximumDocumentBytes;
    this.documentExtractor =
        Objects.requireNonNull(documentExtractor, "documentExtractor must not be null");
    this.useGitCandidates = useGitCandidates;
    this.ignoredDirectories = Set.copyOf(ignoredDirectories);
  }

  /** Scanner for an explicitly selected document root such as {@code .mewcode/knowledge}. */
  public static WorkspaceScanner standaloneDocuments() {
    return new WorkspaceScanner(
        DEFAULT_MAXIMUM_FILE_BYTES,
        DEFAULT_MAXIMUM_DOCUMENT_BYTES,
        new MultiFormatDocumentExtractor(),
        false,
        Set.of());
  }

  public List<WorkspaceScanner.ScannedFile> scan(Path workspace) throws IOException {
    return this.scan(workspace, Map.of());
  }

  /**
   * Scans the workspace, skipping the read and SHA-256 of any file whose size and mtime both match
   * a known fingerprint. Such entries reuse the stored content hash and carry no content.
   *
   * <p>The cheap signal only ever skips work — it never declares a file changed on its own, so the
   * content hash stays the single source of truth. The accepted trade-off: a content change that
   * keeps the same byte size within one mtime clock granule goes unnoticed until either changes.
   * That window is theoretical for editor saves, and it buys back one full-tree read plus one
   * SHA-256 per file on every {@code code_search} call.
   */
  public List<WorkspaceScanner.ScannedFile> scan(
      Path workspace, Map<String, FileFingerprintStore.FileFingerprint> known) throws IOException {
    Objects.requireNonNull(known, "known must not be null");
    final Path root = workspace.toRealPath();
    final List<WorkspaceScanner.ScannedFile> files = new ArrayList<>();
    Optional<List<Path>> gitCandidates = this.useGitCandidates ? this.gitCandidates(root) : Optional.empty();
    if (gitCandidates.isPresent()) {
      for (Path file : gitCandidates.orElseThrow()) {
        try {
          BasicFileAttributes attributes =
              Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
          this.addIfEligible(root, file, attributes, known, files);
        } catch (NoSuchFileException ignored) {
          // A tracked file can disappear between `git ls-files` and the metadata read. Omitting it
          // lets the index synchronization treat it as a normal deletion.
        }
      }
      files.sort(Comparator.comparing(WorkspaceScanner.ScannedFile::path));
      return List.copyOf(files);
    }

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return !directory.equals(root)
                    && WorkspaceScanner.this.ignoredDirectories.contains(
                        WorkspaceScanner.fileName(directory).toLowerCase(Locale.ROOT))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            WorkspaceScanner.this.addIfEligible(root, file, attributes, known, files);
            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(Comparator.comparing(WorkspaceScanner.ScannedFile::path));
    return List.copyOf(files);
  }

  private void addIfEligible(
      Path root,
      Path file,
      BasicFileAttributes attributes,
      Map<String, FileFingerprintStore.FileFingerprint> known,
      List<WorkspaceScanner.ScannedFile> files)
      throws IOException {
    boolean extractable = this.documentExtractor.supports(file);
    long sizeLimit = extractable ? this.maximumDocumentBytes : this.maximumFileBytes;
    if (!attributes.isRegularFile()
        || attributes.isSymbolicLink()
        || attributes.size() > sizeLimit
        || (knownBinary(file) && !extractable)) {
      return;
    }
    String path = portable(root.relativize(file));
    long size = attributes.size();
    long modified = attributes.lastModifiedTime().toMillis();
    FileFingerprintStore.FileFingerprint previous = known.get(path);
    if (previous != null
        && previous.hasCheapSignal()
        && previous.sizeBytes() == size
        && previous.modifiedMillis() == modified) {
      files.add(
          new WorkspaceScanner.ScannedFile(
              path, Optional.empty(), previous.contentHash(), size, modified));
      return;
    }
    byte[] bytes = Files.readAllBytes(file);
    this.decode(file, bytes)
        .ifPresent(
            content ->
                files.add(
                    new WorkspaceScanner.ScannedFile(
                        path, Optional.of(content), sha256(bytes), size, modified)));
  }

  /**
   * Uses Git as the source of truth for repository boundaries. This honors root and nested {@code
   * .gitignore} files, {@code .git/info/exclude}, and the user's standard excludes without losing
   * untracked source files that an agent has just created.
   */
  private Optional<List<Path>> gitCandidates(Path root) throws IOException {
    Process process;
    try {
      process =
          new ProcessBuilder(
                  "git",
                  "-C",
                  root.toString(),
                  "ls-files",
                  "-z",
                  "--cached",
                  "--others",
                  "--exclude-standard")
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException unavailable) {
      return Optional.empty();
    }
    byte[] output = process.getInputStream().readAllBytes();
    try {
      if (process.waitFor() != 0) {
        return Optional.empty();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while listing Git workspace files", interrupted);
    }
    List<Path> files = new ArrayList<>();
    for (String relative : new String(output, StandardCharsets.UTF_8).split("\\x00")) {
      if (!relative.isEmpty()) {
        Path candidate = root.resolve(relative).normalize();
        if (candidate.startsWith(root) && !this.isInIgnoredDirectory(root, candidate)) {
          files.add(candidate);
        }
      }
    }
    return Optional.of(List.copyOf(files));
  }

  private boolean isInIgnoredDirectory(Path root, Path file) {
    Path relative = root.relativize(file);
    for (int index = 0; index < relative.getNameCount() - 1; index++) {
      if (this.ignoredDirectories.contains(
          relative.getName(index).toString().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static Optional<String> decode(byte[] bytes) {
    int sample = Math.min(bytes.length, 8192);

    for (int index = 0; index < sample; index++) {
      if (bytes[index] == 0) {
        return Optional.empty();
      }
    }

    try {
      return Optional.of(
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString());
    } catch (CharacterCodingException var3) {
      return Optional.empty();
    }
  }

  private Optional<String> decode(Path path, byte[] bytes) throws IOException {
    return this.documentExtractor.supports(path)
        ? this.documentExtractor.extract(path, bytes)
        : decode(bytes);
  }

  private static boolean knownBinary(Path path) {
    String name = fileName(path);
    int dot = name.lastIndexOf(46);
    return dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  private static String fileName(Path path) {
    return Objects.requireNonNull(path.getFileName(), "path needs a file name").toString();
  }

  private static String portable(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException var2) {
      throw new IllegalStateException("SHA-256 is unavailable", var2);
    }
  }

  /**
   * One scanned workspace file. {@code content} is empty exactly when the cheap change signal
   * matched a known fingerprint and the read was skipped; in that case {@code fingerprint} is the
   * stored hash, which by construction equals the previous scan's hash.
   */
  public static record ScannedFile(
      String path,
      Optional<String> content,
      String fingerprint,
      long sizeBytes,
      long modifiedMillis) {}
}
