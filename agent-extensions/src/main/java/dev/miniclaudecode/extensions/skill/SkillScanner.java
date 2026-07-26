package dev.miniclaudecode.extensions.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class SkillScanner {
  private static final int METADATA_BYTES = 65536;

  public List<SkillDescriptor> scan(Path root, SkillDescriptor.Source source) throws IOException {
    Objects.requireNonNull(root, "root must not be null");
    Objects.requireNonNull(source, "source must not be null");
    if (!Files.isDirectory(root)) {
      return List.of();
    } else {
      Path realRoot = root.toRealPath();
      List<SkillDescriptor> skills = new ArrayList<>();

      try (Stream<Path> paths = Files.walk(realRoot, 5)) {
        for (Path candidate : paths.filter(SkillScanner::isSkillFile).sorted().toList()) {
          Path realFile = candidate.toRealPath();
          if (realFile.startsWith(realRoot) && !Files.isSymbolicLink(candidate)) {
            parse(realRoot, realFile, source).ifPresent(skills::add);
          }
        }
      }

      skills.sort(
          Comparator.comparing(SkillDescriptor::name)
              .thenComparing(value -> value.file().toString()));
      return List.copyOf(skills);
    }
  }

  private static boolean isSkillFile(Path path) {
    Path name = path.getFileName();
    return name != null
        && Files.isRegularFile(path)
        && name.toString().equalsIgnoreCase("SKILL.md");
  }

  private static Optional<SkillDescriptor> parse(
      Path root, Path file, SkillDescriptor.Source source) {
    try {
      long size = Files.size(file);
      String prefix = readPrefix(file, 65536);
      SkillScanner.Metadata metadata = metadata(file, prefix);
      return Optional.of(
          new SkillDescriptor(metadata.name(), metadata.description(), file, root, source, size));
    } catch (IllegalArgumentException | IOException var7) {
      return Optional.empty();
    }
  }

  private static SkillScanner.Metadata metadata(Path file, String content) {
    List<String> lines = content.lines().toList();
    Map<String, String> frontmatter = frontmatter(lines);
    String name = frontmatter.get("name");
    String description = frontmatter.get("description");
    if (name == null || name.isBlank()) {
      name = heading(lines);
    }

    if (name == null || name.isBlank()) {
      Path parent = file.getParent();
      Path directoryName = parent == null ? null : parent.getFileName();
      name = directoryName == null ? "skill" : directoryName.toString();
    }

    name = normalizeName(unquote(name));
    if (description == null || description.isBlank()) {
      description = firstParagraph(lines);
    }

    if (description == null || description.isBlank()) {
      description = "Local instructions for " + name;
    }

    return new SkillScanner.Metadata(name, unquote(description));
  }

  private static Map<String, String> frontmatter(List<String> lines) {
    if (!lines.isEmpty() && lines.getFirst().strip().equals("---")) {
      LinkedHashMap<String, String> values = new LinkedHashMap<>();

      for (int index = 1; index < lines.size(); index++) {
        String line = lines.get(index);
        if (line.strip().equals("---")) {
          break;
        }

        int separator = line.indexOf(58);
        if (separator > 0) {
          values.put(
              line.substring(0, separator).strip().toLowerCase(Locale.ROOT),
              line.substring(separator + 1).strip());
        }
      }

      return Map.copyOf(values);
    } else {
      return Map.of();
    }
  }

  private static String heading(List<String> lines) {
    return lines.stream()
        .map(String::strip)
        .filter(line -> line.startsWith("# "))
        .map(line -> line.substring(2).strip())
        .findFirst()
        .orElse(null);
  }

  private static String firstParagraph(List<String> lines) {
    int start = 0;
    if (!lines.isEmpty() && lines.getFirst().strip().equals("---")) {
      start = 1;

      while (start < lines.size() && !lines.get(start).strip().equals("---")) {
        start++;
      }

      start = Math.min(lines.size(), start + 1);
    }

    for (int index = start; index < lines.size(); index++) {
      String line = lines.get(index).strip();
      if (!line.isBlank() && !line.startsWith("#")) {
        return line;
      }
    }

    return null;
  }

  private static String readPrefix(Path file, int maximumBytes) throws IOException {
    byte[] bytes;
    try (InputStream input = Files.newInputStream(file)) {
      bytes = input.readNBytes(maximumBytes);
    }

    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException var7) {
      throw new IOException("skill is not valid UTF-8", var7);
    }
  }

  private static String normalizeName(String name) {
    String normalized = name.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-");
    return normalized.replaceAll("^-+|-+$", "");
  }

  private static String unquote(String value) {
    String stripped = value.strip();
    return stripped.length() < 2
            || (!stripped.startsWith("\"") || !stripped.endsWith("\""))
                && (!stripped.startsWith("'") || !stripped.endsWith("'"))
        ? stripped
        : stripped.substring(1, stripped.length() - 1);
  }

  private static record Metadata(String name, String description) {}
}
