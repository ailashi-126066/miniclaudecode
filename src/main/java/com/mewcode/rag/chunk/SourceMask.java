package com.mewcode.rag.chunk;

import java.util.List;
import java.util.Objects;

/**
 * Blanks out string literals and comments while preserving every character position.
 *
 * <p>Everything {@link SymbolChunker} does — matching a declaration pattern against a line,
 * counting braces to find a body, measuring indentation — is a lexical judgement made on raw text,
 * and raw text lies. A {@code }} inside a string closes a block that is still open; a commented-out
 * {@code func old()} looks exactly like a declaration; a brace in a docstring unbalances everything
 * after it. Masking first turns all three into non-problems without teaching the caller anything
 * about lexing.
 *
 * <p>The mask is the same length as the input and keeps newlines, so line and column numbers map
 * one-to-one onto the original. Callers detect structure on the mask and slice content from the
 * original.
 *
 * <p>It is a scanner, not a parser: it does not validate nesting, and an unterminated literal
 * simply masks to end of input. That is the safe direction to fail — masking too much loses a
 * declaration, masking too little corrupts a boundary.
 */
final class SourceMask {

  /**
   * One string-literal form: its delimiters, whether backslash escapes apply, and if it spans
   * lines.
   */
  record Quote(String open, String close, boolean escapes, boolean multiline) {
    Quote {
      Objects.requireNonNull(open, "open must not be null");
      Objects.requireNonNull(close, "close must not be null");
    }

    static Quote of(String delimiter, boolean escapes) {
      return new Quote(delimiter, delimiter, escapes, false);
    }

    static Quote multiline(String delimiter, boolean escapes) {
      return new Quote(delimiter, delimiter, escapes, true);
    }
  }

  /** One block-comment form. */
  record Block(String open, String close) {}

  /**
   * The lexical surface of a language: what starts a comment and what quotes a string.
   *
   * <p>Order matters within {@code quotes}: longer delimiters must come first so that {@code """}
   * is recognised before {@code "}.
   */
  record Syntax(List<String> lineComments, List<Block> blockComments, List<Quote> quotes) {
    Syntax {
      lineComments = List.copyOf(lineComments);
      blockComments = List.copyOf(blockComments);
      quotes = List.copyOf(quotes);
    }
  }

  private SourceMask() {}

  static String mask(String content, Syntax syntax) {
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(syntax, "syntax must not be null");
    char[] masked = content.toCharArray();
    int index = 0;
    while (index < masked.length) {
      int consumed = maskLineComment(content, masked, index, syntax);
      if (consumed == 0) {
        consumed = maskBlockComment(content, masked, index, syntax);
      }
      if (consumed == 0) {
        consumed = maskQuote(content, masked, index, syntax);
      }
      index += consumed > 0 ? consumed : 1;
    }
    return new String(masked);
  }

  private static int maskLineComment(String content, char[] masked, int start, Syntax syntax) {
    for (String marker : syntax.lineComments()) {
      if (content.startsWith(marker, start)) {
        int end = content.indexOf('\n', start);
        int stop = end < 0 ? content.length() : end;
        blank(masked, start, stop);
        return stop - start;
      }
    }
    return 0;
  }

  private static int maskBlockComment(String content, char[] masked, int start, Syntax syntax) {
    for (Block block : syntax.blockComments()) {
      if (content.startsWith(block.open(), start)) {
        int close = content.indexOf(block.close(), start + block.open().length());
        int stop = close < 0 ? content.length() : close + block.close().length();
        blank(masked, start, stop);
        return stop - start;
      }
    }
    return 0;
  }

  private static int maskQuote(String content, char[] masked, int start, Syntax syntax) {
    for (Quote quote : syntax.quotes()) {
      if (!content.startsWith(quote.open(), start)) {
        continue;
      }
      int cursor = start + quote.open().length();
      while (cursor < content.length()) {
        char character = content.charAt(cursor);
        if (quote.escapes() && character == '\\') {
          cursor += 2;
          continue;
        }
        // A single-line literal that reaches a newline is unterminated. Stopping here rather than
        // running on keeps one stray apostrophe — `it's` in a comment-free line — from masking the
        // remainder of the file.
        if (!quote.multiline() && character == '\n') {
          break;
        }
        if (content.startsWith(quote.close(), cursor)) {
          cursor += quote.close().length();
          break;
        }
        cursor++;
      }
      int stop = Math.min(cursor, content.length());
      blank(masked, start, stop);
      return Math.max(stop - start, quote.open().length());
    }
    return 0;
  }

  /** Replaces a span with spaces, leaving newlines so line numbering survives. */
  private static void blank(char[] masked, int start, int stop) {
    for (int index = start; index < stop && index < masked.length; index++) {
      if (masked[index] != '\n' && masked[index] != '\r') {
        masked[index] = ' ';
      }
    }
  }
}
