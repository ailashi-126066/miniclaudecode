package dev.miniclaudecode.rag.chunk;

import com.github.javaparser.ParseProblemException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class FallbackChunker implements DocumentChunker {
  private final DocumentChunker javaChunker;
  private final DocumentChunker textChunker;

  public FallbackChunker() {
    this(new JavaAstChunker(), new StructuredTextChunker());
  }

  public FallbackChunker(DocumentChunker javaChunker, DocumentChunker textChunker) {
    this.javaChunker = Objects.requireNonNull(javaChunker, "javaChunker must not be null");
    this.textChunker = Objects.requireNonNull(textChunker, "textChunker must not be null");
  }

  @Override
  public List<CodeChunk> chunk(String path, String content) {
    if (!path.toLowerCase(Locale.ROOT).endsWith(".java")) {
      return this.textChunker.chunk(path, content);
    } else {
      try {
        return this.javaChunker.chunk(path, content);
      } catch (IllegalArgumentException | ParseProblemException var4) {
        return this.textChunker.chunk(path, content);
      }
    }
  }
}
