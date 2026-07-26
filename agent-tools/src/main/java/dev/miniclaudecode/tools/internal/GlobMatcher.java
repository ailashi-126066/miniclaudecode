package dev.miniclaudecode.tools.internal;

import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class GlobMatcher {
  private final Pattern matcher;

  public GlobMatcher(String pattern) {
    if (pattern != null && !pattern.isBlank()) {
      try {
        this.matcher = Pattern.compile(toRegularExpression(pattern.trim().replace('\\', '/')));
      } catch (PatternSyntaxException var3) {
        throw new IllegalArgumentException("invalid glob pattern: " + pattern, var3);
      }
    } else {
      throw new IllegalArgumentException("glob pattern must not be blank");
    }
  }

  public boolean matches(Path relativePath) {
    String portablePath = relativePath.toString().replace('\\', '/');
    return this.matcher.matcher(portablePath).matches();
  }

  private static String toRegularExpression(String glob) {
    StringBuilder regex = new StringBuilder("^");

    for (int index = 0; index < glob.length(); index++) {
      char character = glob.charAt(index);
      if (character == '*') {
        boolean doubleStar = index + 1 < glob.length() && glob.charAt(index + 1) == '*';
        if (doubleStar) {
          index++;
          boolean followedBySlash = index + 1 < glob.length() && glob.charAt(index + 1) == '/';
          if (followedBySlash) {
            index++;
            regex.append("(?:.*/)?");
          } else {
            regex.append(".*");
          }
        } else {
          regex.append("[^/]*");
        }
      } else if (character == '?') {
        regex.append("[^/]");
      } else if (character == '[') {
        int closing = glob.indexOf(93, index + 1);
        if (closing < 0) {
          throw new IllegalArgumentException("invalid glob pattern: missing ']'");
        }

        String characterClass = glob.substring(index + 1, closing);
        if (characterClass.startsWith("!")) {
          characterClass = "^" + characterClass.substring(1);
        }

        regex.append('[').append(characterClass).append(']');
        index = closing;
      } else {
        if (".(){}+^$|".indexOf(character) >= 0) {
          regex.append('\\');
        }

        regex.append(character);
      }
    }

    return regex.append('$').toString();
  }
}
