package dev.miniclaudecode.tools.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UnifiedPatchApplier {
  private static final Pattern HUNK =
      Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

  public String apply(String original, String patch) {
    List<String> source = new ArrayList<>(Arrays.asList(original.split("\\R", -1)));
    boolean trailingNewline = !source.isEmpty() && source.getLast().isEmpty();
    if (trailingNewline) {
      source.removeLast();
    }

    List<String> patchLines = Arrays.asList(patch.split("\\R", -1));
    List<String> output = new ArrayList<>();
    int sourceCursor = 0;
    boolean foundHunk = false;

    for (int index = 0; index < patchLines.size(); index++) {
      Matcher header = HUNK.matcher(patchLines.get(index));
      if (header.matches()) {
        foundHunk = true;
        int oldStart = Integer.parseInt(header.group(1));
        int targetCursor = Math.max(0, oldStart - 1);
        if (targetCursor < sourceCursor || targetCursor > source.size()) {
          throw new IllegalArgumentException("patch hunk has an invalid source range");
        }

        output.addAll(source.subList(sourceCursor, targetCursor));
        sourceCursor = targetCursor;
        index++;

        while (index < patchLines.size() && !patchLines.get(index).startsWith("@@ ")) {
          String line = patchLines.get(index);
          if (line.startsWith("\\ No newline")) {
            index++;
          } else {
            if (line.isEmpty()) {
              break;
            }

            char marker = line.charAt(0);
            String value = line.substring(1);
            switch (marker) {
              case ' ':
                verifySource(source, sourceCursor, value);
                output.add(value);
                sourceCursor++;
                break;
              case '+':
                output.add(value);
                break;
              case '-':
                verifySource(source, sourceCursor, value);
                sourceCursor++;
                break;
              default:
                throw new IllegalArgumentException("invalid unified patch line");
            }

            index++;
          }
        }

        index--;
      }
    }

    if (!foundHunk) {
      throw new IllegalArgumentException("patch does not contain a unified diff hunk");
    } else {
      output.addAll(source.subList(sourceCursor, source.size()));
      String result = String.join("\n", output);
      return trailingNewline ? result + "\n" : result;
    }
  }

  private static void verifySource(List<String> source, int index, String expected) {
    if (index >= source.size() || !source.get(index).equals(expected)) {
      throw new IllegalArgumentException("patch context no longer matches the target file");
    }
  }
}
