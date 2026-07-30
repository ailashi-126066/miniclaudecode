package dev.miniclaudecode.rag.chunk;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Adapts LangChain4j's recursive document splitter to the stable {@link CodeChunk} contract.
 *
 * <p>The splitter is used for prose and extracted office/PDF text only. Java remains AST-chunked,
 * because a generic paragraph/sentence splitter cannot preserve type and member boundaries.
 */
public final class LangChainDocumentChunker implements DocumentChunker {
  private static final String PATH = "source_path";
  private static final TokenCountEstimator TOKEN_ESTIMATOR = new ApproximateTokenCountEstimator();
  private final DocumentSplitter splitter;
  private final DocumentSplitter parentSplitter;

  public LangChainDocumentChunker() {
    this(450, 50, 1_600);
  }

  public LangChainDocumentChunker(int maximumTokens, int overlapTokens) {
    this(maximumTokens, overlapTokens, Math.max(maximumTokens, 1_600));
  }

  public LangChainDocumentChunker(int maximumTokens, int overlapTokens, int maximumParentTokens) {
    if (maximumTokens < 1
        || overlapTokens < 0
        || overlapTokens >= maximumTokens
        || maximumParentTokens < maximumTokens) {
      throw new IllegalArgumentException("invalid token chunk configuration");
    }
    this.splitter = DocumentSplitters.recursive(maximumTokens, overlapTokens, TOKEN_ESTIMATOR);
    this.parentSplitter = DocumentSplitters.recursive(maximumParentTokens, 0, TOKEN_ESTIMATOR);
  }

  @Override
  public List<CodeChunk> chunk(String path, String content) {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(content, "content must not be null");
    if (content.isBlank()) {
      return List.of();
    }
    List<CodeChunk> chunks = new ArrayList<>();
    for (Region region : regions(content)) {
      List<TextSegment> parentSegments =
          this.parentSplitter.split(Document.from(region.content(), Metadata.from(PATH, path)));
      int parentSearchFrom = 0;
      for (TextSegment parentSegment : parentSegments) {
        String parentText = parentSegment.text().strip();
        if (parentText.isEmpty()) {
          continue;
        }
        int localParentStart = findOffset(region.content(), parentText, parentSearchFrom);
        int parentStart = region.startOffset() + localParentStart;
        int parentEnd = Math.min(content.length(), parentStart + parentText.length());
        String parentHeading = leadingStructure(parentText);
        if (parentHeading.isBlank()) {
          parentHeading = headingBefore(content, parentStart);
        }
        CodeChunk parent =
            CodeChunk.parent(
                path,
                language(path),
                parentHeading,
                lineAt(content, parentStart),
                Math.max(
                    lineAt(content, parentStart),
                    lineAt(content, Math.max(parentStart, parentEnd - 1))),
                parentText);
        chunks.add(parent);
        int childSearchFrom = 0;
        int childIndex = 0;
        for (TextSegment childSegment :
            this.splitter.split(Document.from(parentText, Metadata.from(PATH, path)))) {
          String childText = childSegment.text().strip();
          if (childText.isEmpty()) {
            continue;
          }
          int childStart = findOffset(parentText, childText, childSearchFrom);
          int childEnd = Math.min(parentText.length(), childStart + childText.length());
          chunks.add(
              CodeChunk.child(
                  parent,
                  childIndex++,
                  parent.startLine() + lineAt(parentText, childStart) - 1,
                  parent.startLine()
                      + Math.max(
                          lineAt(parentText, childStart),
                          lineAt(parentText, Math.max(childStart, childEnd - 1)))
                      - 1,
                  childText));
          childSearchFrom = Math.max(childStart + 1, childEnd - 1);
        }
        parentSearchFrom =
            Math.max(localParentStart + 1, localParentStart + parentText.length() - 1);
      }
    }
    return List.copyOf(chunks);
  }

  private static List<Region> regions(String content) {
    List<Region> regions = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < content.length(); ) {
      int end = content.indexOf('\n', index);
      int lineEnd = end < 0 ? content.length() : end;
      String line = content.substring(index, lineEnd).trim();
      if (index > start && isStructureMarker(line)) {
        regions.add(new Region(start, content.substring(start, index)));
        start = index;
      }
      index = end < 0 ? content.length() : end + 1;
    }
    if (start < content.length()) {
      regions.add(new Region(start, content.substring(start)));
    }
    return List.copyOf(regions);
  }

  private static boolean isStructureMarker(String line) {
    return line.startsWith("# ") || line.startsWith("[page ") || line.startsWith("[sheet: ");
  }

  private static int findOffset(String content, String segment, int searchFrom) {
    int found = content.indexOf(segment, searchFrom);
    return found >= 0 ? found : Math.min(searchFrom, Math.max(0, content.length() - 1));
  }

  private static int lineAt(String text, int offset) {
    int lines = 1;
    for (int index = 0; index < Math.min(offset, text.length()); index++) {
      if (text.charAt(index) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  private static String headingBefore(String content, int offset) {
    String latest = "";
    for (String line : content.substring(0, Math.min(offset, content.length())).lines().toList()) {
      String trimmed = line.trim();
      if (trimmed.startsWith("# ")
          || trimmed.startsWith("[page ")
          || trimmed.startsWith("[sheet: ")) {
        latest = trimmed;
      }
    }
    return latest;
  }

  private static String leadingStructure(String content) {
    return content
        .lines()
        .map(String::trim)
        .filter(
            line ->
                line.startsWith("# ") || line.startsWith("[page ") || line.startsWith("[sheet: "))
        .findFirst()
        .orElse("");
  }

  private record Region(int startOffset, String content) {}

  private static String language(String path) {
    int separator = path.lastIndexOf('.');
    return separator < 0 ? "text" : path.substring(separator + 1).toLowerCase(Locale.ROOT);
  }

  /**
   * A local, deterministic token budget for splitting. It intentionally avoids a provider-specific
   * tokenizer: index chunk boundaries must not change when the chat provider is switched.
   */
  private static final class ApproximateTokenCountEstimator implements TokenCountEstimator {
    @Override
    public int estimateTokenCountInText(String text) {
      return Math.max(1, (Objects.requireNonNullElse(text, "").length() + 3) / 4);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
      return estimateTokenCountInText(
          Objects.requireNonNull(message, "message must not be null").toString());
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
      int total = 0;
      for (ChatMessage message : Objects.requireNonNull(messages, "messages must not be null")) {
        total += estimateTokenCountInMessage(message);
      }
      return total;
    }
  }
}
