package dev.miniclaudecode.rag.chunk;

import com.github.javaparser.ParseProblemException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class FallbackChunker implements DocumentChunker {
  private final DocumentChunker javaChunker;
  private final DocumentChunker textChunker;
  private final DocumentChunker brokenJavaChunker;

  public FallbackChunker() {
    this(new JavaAstChunker(), new LangChainDocumentChunker(), new StructuredTextChunker());
  }

  public FallbackChunker(DocumentChunker javaChunker, DocumentChunker textChunker) {
    this(javaChunker, textChunker, textChunker);
  }

  public FallbackChunker(
      DocumentChunker javaChunker, DocumentChunker textChunker, DocumentChunker brokenJavaChunker) {
    this.javaChunker = Objects.requireNonNull(javaChunker, "javaChunker must not be null");
    this.textChunker = Objects.requireNonNull(textChunker, "textChunker must not be null");
    this.brokenJavaChunker =
        Objects.requireNonNull(brokenJavaChunker, "brokenJavaChunker must not be null");
  }

  @Override
  public List<CodeChunk> chunk(String path, String content) {
    if (!path.toLowerCase(Locale.ROOT).endsWith(".java")) {
      return this.textChunker.chunk(path, content);
    } else {
      try {
        return this.javaChunker.chunk(path, content);
      } catch (IllegalArgumentException | ParseProblemException var4) {
        return this.brokenJavaChunker.chunk(path, content);
      }
    }
  }
}
