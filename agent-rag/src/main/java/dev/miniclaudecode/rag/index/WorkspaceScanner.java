package dev.miniclaudecode.rag.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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
      Set.of(".git", ".idea", ".gradle", ".mvn", "target", "build", "out", "node_modules");
  private static final Set<String> BINARY_EXTENSIONS =
      Set.of(
          "class", "jar", "war", "zip", "gz", "png", "jpg", "jpeg", "gif", "ico", "pdf", "exe",
          "dll", "so", "dylib", "woff", "woff2", "ttf", "mp3", "mp4");
  private final long maximumFileBytes;

  public WorkspaceScanner() {
    this(2097152L);
  }

  public WorkspaceScanner(long maximumFileBytes) {
    if (maximumFileBytes < 1L) {
      throw new IllegalArgumentException("maximumFileBytes must be positive");
    } else {
      this.maximumFileBytes = maximumFileBytes;
    }
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
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return !directory.equals(root)
                    && WorkspaceScanner.IGNORED_DIRECTORIES.contains(
                        WorkspaceScanner.fileName(directory).toLowerCase(Locale.ROOT))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            if (attributes.isRegularFile()
                && !attributes.isSymbolicLink()
                && attributes.size() <= WorkspaceScanner.this.maximumFileBytes
                && !WorkspaceScanner.knownBinary(file)) {
              String path = WorkspaceScanner.portable(root.relativize(file));
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
              } else {
                byte[] bytes = Files.readAllBytes(file);
                WorkspaceScanner.decode(bytes)
                    .ifPresent(
                        content ->
                            files.add(
                                new WorkspaceScanner.ScannedFile(
                                    path,
                                    Optional.of(content),
                                    WorkspaceScanner.sha256(bytes),
                                    size,
                                    modified)));
              }
            }

            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(Comparator.comparing(WorkspaceScanner.ScannedFile::path));
    return List.copyOf(files);
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
