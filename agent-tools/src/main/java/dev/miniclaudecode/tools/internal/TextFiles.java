package dev.miniclaudecode.tools.internal;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class TextFiles {
  private TextFiles() {}

  public static String decodeUtf8(byte[] bytes) {
    for (byte value : bytes) {
      if (value == 0) {
        throw new IllegalArgumentException("binary file is not supported");
      }
    }

    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException var5) {
      throw new IllegalArgumentException("binary or non-UTF-8 file is not supported", var5);
    }
  }

  public static String withLineNumbers(String text, int startLine, int maxLines) {
    String[] lines = text.split("\\R", -1);
    int length = lines.length;
    if (length > 0 && lines[length - 1].isEmpty()) {
      length--;
    }

    int from = Math.min(Math.max(0, startLine - 1), length);
    int to = Math.min(length, from + maxLines);
    StringBuilder numbered = new StringBuilder();

    for (int index = from; index < to; index++) {
      if (!numbered.isEmpty()) {
        numbered.append('\n');
      }

      numbered.append(index + 1).append(" | ").append(lines[index]);
    }

    return numbered.toString();
  }
}
