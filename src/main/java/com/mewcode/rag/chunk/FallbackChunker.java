package com.mewcode.rag.chunk;

import com.github.javaparser.ParseProblemException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Routes each file to the most structure-aware chunker that can handle it.
 *
 * <p>Four tiers, in descending fidelity: Java gets JavaParser's AST, languages with a bundled
 * Tree-sitter grammar get a concrete syntax tree, the remaining languages recognised by {@link
 * SymbolChunker} get declaration boundaries from patterns, and prose or extracted documents get the
 * recursive splitter. Every syntax-aware tier has a non-native fallback.
 */
public final class FallbackChunker implements DocumentChunker {
  private final DocumentChunker javaChunker;
  private final DocumentChunker treeSitterChunker;
  private final DocumentChunker symbolChunker;
  private final DocumentChunker textChunker;
  private final DocumentChunker brokenSourceChunker;

  public FallbackChunker() {
    this(new JavaAstChunker(), new LangChainDocumentChunker(), new StructuredTextChunker());
  }

  public FallbackChunker(DocumentChunker javaChunker, DocumentChunker textChunker) {
    this(javaChunker, textChunker, textChunker);
  }

  public FallbackChunker(
      DocumentChunker javaChunker,
      DocumentChunker textChunker,
      DocumentChunker brokenSourceChunker) {
    this(javaChunker, new SymbolChunker(textChunker), textChunker, brokenSourceChunker);
  }

  public FallbackChunker(
      DocumentChunker javaChunker,
      DocumentChunker symbolChunker,
      DocumentChunker textChunker,
      DocumentChunker brokenSourceChunker) {
    this(
        javaChunker,
        new TreeSitterChunker(symbolChunker),
        symbolChunker,
        textChunker,
        brokenSourceChunker);
  }

  public FallbackChunker(
      DocumentChunker javaChunker,
      DocumentChunker treeSitterChunker,
      DocumentChunker symbolChunker,
      DocumentChunker textChunker,
      DocumentChunker brokenSourceChunker) {
    this.javaChunker = Objects.requireNonNull(javaChunker, "javaChunker must not be null");
    this.treeSitterChunker =
        Objects.requireNonNull(treeSitterChunker, "treeSitterChunker must not be null");
    this.symbolChunker = Objects.requireNonNull(symbolChunker, "symbolChunker must not be null");
    this.textChunker = Objects.requireNonNull(textChunker, "textChunker must not be null");
    this.brokenSourceChunker =
        Objects.requireNonNull(brokenSourceChunker, "brokenSourceChunker must not be null");
  }

  @Override
  public List<CodeChunk> chunk(String path, String content) {
    if (path.toLowerCase(Locale.ROOT).endsWith(".java")) {
      try {
        return this.javaChunker.chunk(path, content);
      } catch (IllegalArgumentException | ParseProblemException unparseable) {
        return this.brokenSourceChunker.chunk(path, content);
      }
    }
    if (TreeSitterChunker.supports(path)) {
      return this.treeSitterChunker.chunk(path, content);
    }
    return SymbolChunker.supports(path)
        ? this.symbolChunker.chunk(path, content)
        : this.textChunker.chunk(path, content);
  }
}
