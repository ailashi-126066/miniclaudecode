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

public final class SkillCatalog {
  private final Map<String, SkillDescriptor> skills;
  private final int maximumLoadedBytes;

  public SkillCatalog(List<SkillDescriptor> descriptors) {
    this(descriptors, 65536);
  }

  public SkillCatalog(List<SkillDescriptor> descriptors, int maximumLoadedBytes) {
    if (maximumLoadedBytes < 1) {
      throw new IllegalArgumentException("maximumLoadedBytes must be positive");
    } else {
      this.maximumLoadedBytes = maximumLoadedBytes;
      Map<String, SkillDescriptor> selected = new LinkedHashMap<>();
      descriptors.stream()
          .sorted(
              Comparator.<SkillDescriptor>comparingInt(value -> value.source().priority())
                  .thenComparing(value -> value.file().toString()))
          .forEach(
              descriptor ->
                  selected.merge(
                      descriptor.name(),
                      descriptor,
                      (current, candidate) ->
                          (SkillDescriptor)
                              (candidate.source().priority() >= current.source().priority()
                                  ? candidate
                                  : current)));
      this.skills = Map.copyOf(selected);
    }
  }

  public static SkillCatalog discover(Path workspace, Path userSkills) throws IOException {
    SkillScanner scanner = new SkillScanner();
    List<SkillDescriptor> descriptors = new ArrayList<>();
    descriptors.addAll(scanner.scan(userSkills, SkillDescriptor.Source.USER));
    descriptors.addAll(
        scanner.scan(
            workspace.resolve(".claude/skills"), SkillDescriptor.Source.CLAUDE_COMPATIBILITY));
    descriptors.addAll(
        scanner.scan(
            workspace.resolve(".mini-claude-code/skills"), SkillDescriptor.Source.PROJECT));
    return new SkillCatalog(descriptors);
  }

  public List<SkillDescriptor> list() {
    return this.skills.values().stream()
        .sorted(Comparator.comparing(SkillDescriptor::name))
        .toList();
  }

  public SkillCatalog.LoadedSkill load(String name) throws IOException {
    SkillDescriptor descriptor = this.skills.get(requireName(name));
    if (descriptor == null) {
      throw new IllegalArgumentException("unknown skill: " + name);
    } else {
      Path realRoot = descriptor.root().toRealPath();
      Path realFile = descriptor.file().toRealPath();
      if (realFile.startsWith(realRoot) && !Files.isSymbolicLink(descriptor.file())) {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(realFile)) {
          bytes = input.readNBytes(this.maximumLoadedBytes + 1);
        }

        boolean truncated = bytes.length > this.maximumLoadedBytes;
        int length = Math.min(bytes.length, this.maximumLoadedBytes);
        String content = decodePrefix(bytes, length);
        if (truncated) {
          content = content + "\n\n[Skill truncated at " + this.maximumLoadedBytes + " bytes]";
        }

        return new SkillCatalog.LoadedSkill(descriptor, content, truncated, descriptor.sizeBytes());
      } else {
        throw new SecurityException("skill path escapes its configured root");
      }
    }
  }

  public String promptIndex() {
    if (this.skills.isEmpty()) {
      return "Available local skills: none.";
    } else {
      StringBuilder index =
          new StringBuilder(
              "Available local skills (descriptions only; use skills:load_skill to load"
                  + " instructions):\n");

      for (SkillDescriptor descriptor : this.list()) {
        index
            .append("- ")
            .append(descriptor.name())
            .append(": ")
            .append(descriptor.description())
            .append(" [")
            .append(descriptor.source().name().toLowerCase(Locale.ROOT))
            .append("]\n");
      }

      return index.toString().stripTrailing();
    }
  }

  private static String requireName(String name) {
    if (name != null && !name.isBlank()) {
      return name.trim().toLowerCase(Locale.ROOT);
    } else {
      throw new IllegalArgumentException("skill name must not be blank");
    }
  }

  private static String decodePrefix(byte[] bytes, int length) throws IOException {
    for (int end = length; end > Math.max(0, length - 4); end--) {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, end))
            .toString();
      } catch (CharacterCodingException var4) {
      }
    }

    throw new IOException("skill is not valid UTF-8");
  }

  public static record LoadedSkill(
      SkillDescriptor descriptor, String content, boolean truncated, long totalBytes) {
    public LoadedSkill(
        SkillDescriptor descriptor, String content, boolean truncated, long totalBytes) {
      Objects.requireNonNull(descriptor);
      Objects.requireNonNull(content);
      this.descriptor = descriptor;
      this.content = content;
      this.truncated = truncated;
      this.totalBytes = totalBytes;
    }
  }
}
