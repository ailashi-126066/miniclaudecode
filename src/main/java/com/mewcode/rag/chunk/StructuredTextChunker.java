package com.mewcode.rag.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StructuredTextChunker implements DocumentChunker {
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
  private final int maximumLines;
  private final int overlapLines;

  public StructuredTextChunker() {
    this(120, 20);
  }

  public StructuredTextChunker(int maximumLines, int overlapLines) {
    if (maximumLines >= 1 && overlapLines >= 0 && overlapLines < maximumLines) {
      this.maximumLines = maximumLines;
      this.overlapLines = overlapLines;
    } else {
      throw new IllegalArgumentException("invalid chunk window");
    }
  }

  @Override
  public List<CodeChunk> chunk(String path, String content) {
    List<String> lines = content.lines().toList();
    if (lines.isEmpty()) {
      return List.of();
    } else {
      List<StructuredTextChunker.Section> sections =
          markdown(path)
              ? sections(lines)
              : List.of(new StructuredTextChunker.Section(1, lines.size(), ""));
      List<CodeChunk> chunks = new ArrayList<>();

      for (StructuredTextChunker.Section section : sections) {
        int start = section.startLine();

        while (start <= section.endLine()) {
          int end = Math.min(section.endLine(), start + this.maximumLines - 1);
          String text = String.join("\n", lines.subList(start - 1, end));
          CodeChunk.Kind kind =
              section.heading().isBlank() ? CodeChunk.Kind.TEXT : CodeChunk.Kind.SECTION;
          chunks.add(
              CodeChunk.create(
                  path, language(path), kind, "", "", section.heading(), start, end, text));
          if (end == section.endLine()) {
            break;
          }

          start = end - this.overlapLines + 1;
        }
      }

      return List.copyOf(chunks);
    }
  }

  private static List<StructuredTextChunker.Section> sections(List<String> lines) {
    List<StructuredTextChunker.Section> sections = new ArrayList<>();
    int start = 1;
    String heading = "";

    for (int index = 0; index < lines.size(); index++) {
      Matcher matcher = HEADING.matcher(lines.get(index));
      if (matcher.matches()) {
        int line = index + 1;
        if (line > start) {
          sections.add(new StructuredTextChunker.Section(start, line - 1, heading));
        }

        start = line;
        heading = matcher.group(2);
      }
    }

    sections.add(new StructuredTextChunker.Section(start, lines.size(), heading));
    return sections;
  }

  private static boolean markdown(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".md") || lower.endsWith(".markdown");
  }

  private static String language(String path) {
    int separator = path.lastIndexOf(46);
    return separator < 0 ? "text" : path.substring(separator + 1).toLowerCase(Locale.ROOT);
  }

  private static record Section(int startLine, int endLine, String heading) {}
}
