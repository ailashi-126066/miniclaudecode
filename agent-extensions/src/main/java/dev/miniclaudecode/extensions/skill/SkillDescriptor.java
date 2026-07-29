package dev.miniclaudecode.extensions.skill;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record SkillDescriptor(
    String name,
    String description,
    Path file,
    Path root,
    SkillDescriptor.Source source,
    long sizeBytes,
    List<String> tags,
    List<String> triggers,
    List<String> boundaries,
    List<String> examples) {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

  public SkillDescriptor(
      String name,
      String description,
      Path file,
      Path root,
      SkillDescriptor.Source source,
      long sizeBytes) {
    this(
        name,
        description,
        file,
        root,
        source,
        sizeBytes,
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  public SkillDescriptor(
      String name,
      String description,
      Path file,
      Path root,
      SkillDescriptor.Source source,
      long sizeBytes,
      List<String> tags,
      List<String> triggers,
      List<String> boundaries,
      List<String> examples) {
    if (name != null && NAME.matcher(name).matches()) {
      description = requireText(description, "description");
      file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
      root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
      Objects.requireNonNull(source, "source must not be null");
      if (file.startsWith(root) && sizeBytes >= 0L) {
        this.name = name;
        this.description = description;
        this.file = file;
        this.root = root;
        this.source = source;
        this.sizeBytes = sizeBytes;
        this.tags = normalized(tags);
        this.triggers = normalized(triggers);
        this.boundaries = normalized(boundaries);
        this.examples = normalized(examples);
      } else {
        throw new IllegalArgumentException("skill file must be inside its source root");
      }
    } else {
      throw new IllegalArgumentException("skill name must match " + NAME.pattern());
    }
  }

  @Override
  public List<String> tags() {
    return new ArrayList<>(tags);
  }

  @Override
  public List<String> triggers() {
    return new ArrayList<>(triggers);
  }

  @Override
  public List<String> boundaries() {
    return new ArrayList<>(boundaries);
  }

  @Override
  public List<String> examples() {
    return new ArrayList<>(examples);
  }

  private static String requireText(String value, String field) {
    if (value != null && !value.isBlank()) {
      return value.trim();
    } else {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static List<String> normalized(List<String> values) {
    Objects.requireNonNull(values, "skill metadata must not be null");
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .distinct()
        .toList();
  }

  public static enum Source {
    USER(0),
    CLAUDE_COMPATIBILITY(1),
    PROJECT(2);

    private final int priority;

    private Source(int priority) {
      this.priority = priority;
    }

    public int priority() {
      return this.priority;
    }
  }
}
