package dev.miniclaudecode.tools.diff;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class UnifiedDiffService {
  private static final int CONTEXT_LINES = 3;

  public UnifiedDiffService.DiffResult create(String displayPath, String before, String after) {
    if (displayPath == null || displayPath.isBlank()) {
      throw new IllegalArgumentException("displayPath must not be blank");
    } else if (before != null && after != null) {
      String beforeHash = FileHashes.sha256(before.getBytes(StandardCharsets.UTF_8));
      if (before.equals(after)) {
        return new UnifiedDiffService.DiffResult("", beforeHash, FileHashes.sha256(new byte[0]));
      } else {
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);
        int oldChangeEnd = oldLines.size() - suffix;
        int newChangeEnd = newLines.size() - suffix;
        int oldFrom = Math.max(0, prefix - 3);
        int newFrom = Math.max(0, prefix - 3);
        int oldTo = Math.min(oldLines.size(), oldChangeEnd + 3);
        int newTo = Math.min(newLines.size(), newChangeEnd + 3);
        StringBuilder diff =
            new StringBuilder()
                .append("--- a/")
                .append(displayPath)
                .append('\n')
                .append("+++ b/")
                .append(displayPath)
                .append('\n')
                .append("@@ -")
                .append(rangeStart(oldFrom, oldTo))
                .append(',')
                .append(oldTo - oldFrom)
                .append(" +")
                .append(rangeStart(newFrom, newTo))
                .append(',')
                .append(newTo - newFrom)
                .append(" @@\n");

        for (int index = oldFrom; index < prefix; index++) {
          diff.append(' ').append(oldLines.get(index)).append('\n');
        }

        for (int index = prefix; index < oldChangeEnd; index++) {
          diff.append('-').append(oldLines.get(index)).append('\n');
        }

        for (int index = prefix; index < newChangeEnd; index++) {
          diff.append('+').append(newLines.get(index)).append('\n');
        }

        for (int index = 0; index < Math.min(3, suffix); index++) {
          diff.append(' ').append(oldLines.get(oldChangeEnd + index)).append('\n');
        }

        String text = diff.toString();
        return new UnifiedDiffService.DiffResult(
            text, beforeHash, FileHashes.sha256(text.getBytes(StandardCharsets.UTF_8)));
      }
    } else {
      throw new IllegalArgumentException("diff content must not be null");
    }
  }

  private static List<String> lines(String value) {
    if (value.isEmpty()) {
      return List.of();
    } else {
      List<String> lines = new ArrayList<>(Arrays.asList(value.split("\\R", -1)));
      if (!lines.isEmpty() && lines.getLast().isEmpty()) {
        lines.removeLast();
      }

      return lines;
    }
  }

  private static int commonPrefix(List<String> left, List<String> right) {
    int limit = Math.min(left.size(), right.size());
    int index = 0;

    while (index < limit && left.get(index).equals(right.get(index))) {
      index++;
    }

    return index;
  }

  private static int commonSuffix(List<String> left, List<String> right, int prefix) {
    int limit = Math.min(left.size(), right.size()) - prefix;
    int count = 0;

    while (count < limit
        && left.get(left.size() - count - 1).equals(right.get(right.size() - count - 1))) {
      count++;
    }

    return count;
  }

  private static int rangeStart(int from, int to) {
    return from == 0 && to == 0 ? 0 : from + 1;
  }

  public static record DiffResult(String unifiedDiff, String beforeContentHash, String diffHash) {}
}
