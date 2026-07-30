package dev.miniclaudecode.persistence.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Project-local ACE bullet store with deterministic deduplication and merge-on-repeat curation. */
public final class AceBulletStore {
  private final Path file;
  private final ObjectMapper mapper = new ObjectMapper();

  public AceBulletStore(Path workspace) {
    this.file =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize()
            .resolve(".miniclaudecode/bullets/ace.jsonl");
  }

  public synchronized AceBullet curate(AceBullet candidate) {
    Objects.requireNonNull(candidate, "candidate must not be null");
    List<AceBullet> bullets = new ArrayList<>(list());
    for (int index = 0; index < bullets.size(); index++) {
      AceBullet existing = bullets.get(index);
      if (existing.id().equals(candidate.id())) {
        AceBullet merged = existing.merge(candidate);
        bullets.set(index, merged);
        write(bullets);
        return merged;
      }
    }
    bullets.add(candidate);
    write(bullets);
    return candidate;
  }

  /** Stores an untrusted lesson candidate. Candidates never enter model context until approved. */
  public synchronized AceBullet propose(AceBullet candidate) {
    Objects.requireNonNull(candidate, "candidate must not be null");
    AceBullet pending = withState(candidate, AceBullet.State.PENDING_REVIEW);
    List<AceBullet> bullets = new ArrayList<>(list());
    for (int index = 0; index < bullets.size(); index++) {
      AceBullet existing = bullets.get(index);
      if (existing.id().equals(pending.id())) {
        AceBullet merged = withState(existing.merge(pending), AceBullet.State.PENDING_REVIEW);
        bullets.set(index, merged);
        write(bullets);
        return merged;
      }
    }
    bullets.add(pending);
    write(bullets);
    return pending;
  }

  public synchronized boolean approve(String id) {
    Objects.requireNonNull(id, "id must not be null");
    List<AceBullet> bullets = new ArrayList<>(list());
    for (int index = 0; index < bullets.size(); index++) {
      AceBullet bullet = bullets.get(index);
      if (bullet.id().equals(id) && bullet.state() == AceBullet.State.PENDING_REVIEW) {
        if (conflictsWithActive(bullet, bullets)) {
          return false;
        }
        bullets.set(index, withState(bullet, AceBullet.State.ACTIVE));
        write(bullets);
        return true;
      }
    }
    return false;
  }

  public synchronized List<AceBullet> pending() {
    return list().stream()
        .filter(value -> value.state() == AceBullet.State.PENDING_REVIEW)
        .toList();
  }

  public synchronized List<AceBullet> search(String query, int limit) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    if (limit < 1 || limit > 20) {
      throw new IllegalArgumentException("limit must be between 1 and 20");
    }
    Set<String> terms = terms(query);
    return list().stream()
        .filter(bullet -> bullet.state() == AceBullet.State.ACTIVE)
        .map(bullet -> new Scored(bullet, score(terms, bullet)))
        .filter(value -> value.score > 0)
        .sorted(
            Comparator.comparingInt(Scored::score)
                .reversed()
                .thenComparing(value -> value.bullet.updatedAt(), Comparator.reverseOrder()))
        .limit(limit)
        .map(Scored::bullet)
        .toList();
  }

  /** Archives a lesson without deleting its audit trail. Archived bullets are not retrieved. */
  public synchronized boolean archive(String id) {
    return transition(id, null, AceBullet.State.ARCHIVED);
  }

  private boolean transition(String id, AceBullet.State expected, AceBullet.State target) {
    Objects.requireNonNull(id, "id must not be null");
    List<AceBullet> bullets = new ArrayList<>(list());
    for (int index = 0; index < bullets.size(); index++) {
      AceBullet bullet = bullets.get(index);
      if (bullet.id().equals(id)
          && bullet.state() != target
          && (expected == null || bullet.state() == expected)) {
        bullets.set(index, withState(bullet, target));
        write(bullets);
        return true;
      }
    }
    return false;
  }

  public synchronized List<AceBullet> list() {
    if (!Files.isRegularFile(this.file)) {
      return List.of();
    }
    try {
      List<AceBullet> bullets = new ArrayList<>();
      for (String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          try {
            bullets.add(decode(line));
          } catch (RuntimeException ignored) {
            // One malformed historical bullet must not disable retrieval.
          }
        }
      }
      return List.copyOf(bullets);
    } catch (IOException error) {
      throw new IllegalStateException("cannot read ACE bullets", error);
    }
  }

  private void write(List<AceBullet> bullets) {
    try {
      Path parent = Objects.requireNonNull(this.file.getParent(), "ACE bullet file has no parent");
      Files.createDirectories(parent);
      String content =
          bullets.stream().map(this::encode).reduce("", (left, right) -> left + right + "\n");
      Path temporary = Files.createTempFile(parent, ".ace-", ".tmp");
      try {
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.move(
            temporary,
            this.file,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException error) {
      throw new IllegalStateException("cannot save ACE bullets", error);
    }
  }

  private String encode(AceBullet bullet) {
    ObjectNode node = this.mapper.createObjectNode();
    node.put("id", bullet.id());
    node.put("trigger", bullet.trigger());
    node.put("lesson", bullet.lesson());
    node.put("occurrences", bullet.occurrences());
    node.put("createdAt", bullet.createdAt().toString());
    node.put("updatedAt", bullet.updatedAt().toString());
    node.put("confidence", bullet.confidence());
    node.put("state", bullet.state().name());
    bullet.evidence().forEach(node.putArray("evidence")::add);
    bullet.applicablePaths().forEach(node.putArray("applicablePaths")::add);
    try {
      return this.mapper.writeValueAsString(node);
    } catch (IOException error) {
      throw new IllegalStateException("cannot encode ACE bullet", error);
    }
  }

  private AceBullet decode(String line) {
    try {
      JsonNode node = this.mapper.readTree(line);
      List<String> evidence = new ArrayList<>();
      node.path("evidence").forEach(value -> evidence.add(value.asText()));
      List<String> applicablePaths = new ArrayList<>();
      node.path("applicablePaths").forEach(value -> applicablePaths.add(value.asText()));
      return new AceBullet(
          node.path("id").asText(),
          node.path("trigger").asText(),
          node.path("lesson").asText(),
          evidence,
          node.path("occurrences").asInt(1),
          Instant.parse(node.path("createdAt").asText()),
          Instant.parse(node.path("updatedAt").asText()),
          node.path("confidence").isNumber() ? node.path("confidence").asDouble() : 0.55,
          applicablePaths,
          parseState(node.path("state").asText("ACTIVE")));
    } catch (IOException error) {
      throw new IllegalArgumentException("malformed ACE bullet", error);
    }
  }

  private static AceBullet.State parseState(String value) {
    try {
      return AceBullet.State.valueOf(value);
    } catch (IllegalArgumentException error) {
      return AceBullet.State.ACTIVE;
    }
  }

  private static AceBullet withState(AceBullet bullet, AceBullet.State state) {
    return new AceBullet(
        bullet.id(),
        bullet.trigger(),
        bullet.lesson(),
        bullet.evidence(),
        bullet.occurrences(),
        bullet.createdAt(),
        Instant.now(),
        bullet.confidence(),
        bullet.applicablePaths(),
        state);
  }

  /**
   * A same-trigger, different-lesson candidate can silently override a stable project rule. Keep it
   * pending until a human archives or corrects the existing rule instead of choosing a winner based
   * on model wording.
   */
  private static boolean conflictsWithActive(AceBullet candidate, List<AceBullet> bullets) {
    return bullets.stream()
        .filter(value -> value.state() == AceBullet.State.ACTIVE)
        .anyMatch(
            value ->
                value.trigger().equalsIgnoreCase(candidate.trigger())
                    && !value.lesson().equalsIgnoreCase(candidate.lesson()));
  }

  private static int score(Set<String> terms, AceBullet bullet) {
    Set<String> content =
        terms(bullet.trigger() + " " + bullet.lesson() + " " + String.join(" ", bullet.evidence()));
    int score = 0;
    for (String term : terms) {
      if (content.contains(term)) {
        score++;
      }
    }
    return score;
  }

  private static Set<String> terms(String value) {
    Set<String> terms = new LinkedHashSet<>();
    for (String part :
        Objects.requireNonNullElse(value, "")
            .toLowerCase(Locale.ROOT)
            .split("[^\\p{L}\\p{N}_.-]+")) {
      if (part.length() >= 2) {
        terms.add(part);
      }
    }
    return Set.copyOf(terms);
  }

  private record Scored(AceBullet bullet, int score) {}
}
