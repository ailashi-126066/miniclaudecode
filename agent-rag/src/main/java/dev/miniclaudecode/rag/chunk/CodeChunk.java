package dev.miniclaudecode.rag.chunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record CodeChunk(
    String id,
    String path,
    String language,
    CodeChunk.Kind kind,
    String packageName,
    String owner,
    String symbol,
    int startLine,
    int endLine,
    String content,
    String parentChunkId,
    CodeChunk.Role role,
    int childIndex) {
  private static final Pattern IDENTIFIER_BOUNDARY =
      Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");

  public CodeChunk(
      String id,
      String path,
      String language,
      CodeChunk.Kind kind,
      String packageName,
      String owner,
      String symbol,
      int startLine,
      int endLine,
      String content) {
    this(
        id,
        path,
        language,
        kind,
        packageName,
        owner,
        symbol,
        startLine,
        endLine,
        content,
        "",
        Role.STANDALONE,
        0);
  }

  public CodeChunk(
      String id,
      String path,
      String language,
      CodeChunk.Kind kind,
      String packageName,
      String owner,
      String symbol,
      int startLine,
      int endLine,
      String content,
      String parentChunkId,
      CodeChunk.Role role,
      int childIndex) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(language, "language must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    packageName = Objects.requireNonNullElse(packageName, "");
    owner = Objects.requireNonNullElse(owner, "");
    symbol = Objects.requireNonNullElse(symbol, "");
    Objects.requireNonNull(content, "content must not be null");
    parentChunkId = Objects.requireNonNullElse(parentChunkId, "");
    Objects.requireNonNull(role, "role must not be null");
    if (startLine >= 1
        && endLine >= startLine
        && childIndex >= 0
        && ((role == Role.CHILD) == !parentChunkId.isBlank())) {
      this.id = id;
      this.path = path;
      this.language = language;
      this.kind = kind;
      this.packageName = packageName;
      this.owner = owner;
      this.symbol = symbol;
      this.startLine = startLine;
      this.endLine = endLine;
      this.content = content;
      this.parentChunkId = parentChunkId;
      this.role = role;
      this.childIndex = childIndex;
    } else {
      throw new IllegalArgumentException("invalid chunk line range");
    }
  }

  public static CodeChunk create(
      String path,
      String language,
      CodeChunk.Kind kind,
      String packageName,
      String owner,
      String symbol,
      int startLine,
      int endLine,
      String content) {
    String identity =
        path + "\u0000" + kind + "\u0000" + owner + "\u0000" + symbol + "\u0000" + startLine;
    return new CodeChunk(
        sha256(identity),
        path,
        language,
        kind,
        packageName,
        owner,
        symbol,
        startLine,
        endLine,
        content);
  }

  public static CodeChunk parent(
      String path, String language, String symbol, int startLine, int endLine, String content) {
    String identity = path + "\u0000PARENT\u0000" + symbol + "\u0000" + startLine;
    return new CodeChunk(
        sha256(identity),
        path,
        language,
        Kind.SECTION,
        "",
        "",
        symbol,
        startLine,
        endLine,
        content,
        "",
        Role.PARENT,
        0);
  }

  public static CodeChunk child(
      CodeChunk parent, int childIndex, int startLine, int endLine, String content) {
    Objects.requireNonNull(parent, "parent must not be null");
    String identity = parent.id() + "\u0000CHILD\u0000" + childIndex;
    return new CodeChunk(
        sha256(identity),
        parent.path(),
        parent.language(),
        parent.kind(),
        parent.packageName(),
        parent.owner(),
        parent.symbol(),
        startLine,
        endLine,
        content,
        parent.id(),
        Role.CHILD,
        childIndex);
  }

  public String embeddingText() {
    String qualified =
        this.packageName.isBlank() ? this.owner : this.packageName + "." + this.owner;
    return this.path + "\n" + qualified + " " + this.symbol + "\n" + this.content;
  }

  public String lexicalText() {
    String value = this.embeddingText();
    return value
        + "\n"
        + String.join(" ", IDENTIFIER_BOUNDARY.split(value)).toLowerCase(Locale.ROOT);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException var2) {
      throw new IllegalStateException("SHA-256 is unavailable", var2);
    }
  }

  public static enum Kind {
    TYPE,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    SECTION,
    TEXT;
  }

  public static enum Role {
    STANDALONE,
    PARENT,
    CHILD;
  }
}
