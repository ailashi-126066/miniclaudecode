package dev.miniclaudecode.persistence.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.session.TurnId;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content-addressed cross-session memory store with bounded lexical retrieval. */
public final class JsonlMemoryStore {
  private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_.-]+");
  private static final int MAX_RECORDS_READ = 2_000;
  private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
  private final Path file;
  private final SecretRedactor redactor;
  private final Set<String> secrets;
  private final ObjectMapper mapper = new ObjectMapper();

  public JsonlMemoryStore(Path file, SecretRedactor redactor, Set<String> secrets) {
    this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath().normalize();
    this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
    this.secrets = Set.copyOf(Objects.requireNonNull(secrets, "secrets must not be null"));
  }

  public boolean save(MemoryRecord memory) {
    Objects.requireNonNull(memory, "memory must not be null");
    ReentrantLock processLock = LOCKS.computeIfAbsent(this.file, ignored -> new ReentrantLock());
    processLock.lock();
    try {
      if (list().stream().anyMatch(existing -> existing.id().equals(memory.id()))) {
        return false;
      }
      Path parent = this.file.getParent();
      if (parent == null) {
        throw new IllegalArgumentException("memory file must have a parent");
      }
      Files.createDirectories(parent);
      byte[] bytes = (encode(redacted(memory)) + "\n").getBytes(StandardCharsets.UTF_8);
      try (FileChannel channel =
              FileChannel.open(
                  this.file,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.APPEND);
          FileLock ignored = channel.lock()) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(false);
      }
      return true;
    } catch (IOException error) {
      throw new UncheckedIOException("cannot append agent memory", error);
    } finally {
      processLock.unlock();
    }
  }

  public List<SearchHit> search(String query, int limit) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    if (limit < 1 || limit > 20) {
      throw new IllegalArgumentException("memory search limit must be between 1 and 20");
    }
    Set<String> queryTerms = terms(query);
    return list().stream()
        .map(memory -> new SearchHit(memory, score(queryTerms, memory)))
        .filter(hit -> hit.score() > 0)
        .sorted(
            Comparator.comparingDouble(SearchHit::score)
                .reversed()
                .thenComparing(hit -> hit.memory().createdAt(), Comparator.reverseOrder()))
        .limit(limit)
        .toList();
  }

  public List<MemoryRecord> list() {
    if (!Files.isRegularFile(this.file)) {
      return List.of();
    }
    try {
      List<String> lines = Files.readAllLines(this.file, StandardCharsets.UTF_8);
      List<MemoryRecord> records = new ArrayList<>();
      int start = Math.max(0, lines.size() - MAX_RECORDS_READ);
      for (int index = start; index < lines.size(); index++) {
        String line = lines.get(index);
        if (!line.isBlank()) {
          try {
            records.add(decode(line));
          } catch (RuntimeException ignored) {
            // One malformed memory must not make every previous session unusable.
          }
        }
      }
      return List.copyOf(records);
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read agent memory", error);
    }
  }

  private MemoryRecord redacted(MemoryRecord memory) {
    return new MemoryRecord(
        memory.id(),
        memory.category(),
        this.redactor.redact(memory.objective(), this.secrets),
        this.redactor.redact(memory.summary(), this.secrets),
        memory.evidence().stream().map(value -> this.redactor.redact(value, this.secrets)).toList(),
        memory.sourceSession(),
        memory.sourceTurn(),
        memory.createdAt());
  }

  private String encode(MemoryRecord memory) {
    ObjectNode node = this.mapper.createObjectNode();
    node.put("id", memory.id());
    node.put("category", memory.category().name());
    node.put("objective", memory.objective());
    node.put("summary", memory.summary());
    var evidence = node.putArray("evidence");
    memory.evidence().forEach(evidence::add);
    node.put("sourceSession", memory.sourceSession().value());
    node.put("sourceTurn", memory.sourceTurn().value());
    node.put("createdAt", memory.createdAt().toString());
    try {
      return this.mapper.writeValueAsString(node);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private MemoryRecord decode(String line) {
    try {
      JsonNode node = this.mapper.readTree(line);
      List<String> evidence = new ArrayList<>();
      node.path("evidence").forEach(value -> evidence.add(value.asText()));
      return new MemoryRecord(
          node.path("id").asText(),
          MemoryRecord.Category.valueOf(node.path("category").asText()),
          node.path("objective").asText(),
          node.path("summary").asText(),
          evidence,
          SessionId.of(node.path("sourceSession").asText()),
          TurnId.of(node.path("sourceTurn").asLong()),
          Instant.parse(node.path("createdAt").asText()));
    } catch (IOException error) {
      throw new IllegalArgumentException("malformed memory record", error);
    }
  }

  private static double score(Set<String> query, MemoryRecord memory) {
    double score = overlap(query, terms(memory.objective())) * 4.0;
    score += overlap(query, terms(memory.summary())) * 2.0;
    score +=
        memory.evidence().stream().mapToInt(value -> overlap(query, terms(value))).sum() * 1.25;
    if (memory.category() == MemoryRecord.Category.USER_PREFERENCE) {
      score += 0.25;
    }
    return score;
  }

  private static int overlap(Set<String> left, Set<String> right) {
    int count = 0;
    for (String value : left) {
      if (right.contains(value)) {
        count++;
      }
    }
    return count;
  }

  private static Set<String> terms(String value) {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    Matcher matcher = TOKEN.matcher(Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String token = matcher.group();
      if (token.length() >= 2) {
        values.add(token);
      }
      if (containsHan(token)) {
        for (int index = 0; index + 1 < token.length(); index++) {
          values.add(token.substring(index, index + 2));
        }
      }
    }
    return Set.copyOf(values);
  }

  private static boolean containsHan(String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
  }

  public record SearchHit(MemoryRecord memory, double score) {
    public SearchHit {
      Objects.requireNonNull(memory);
    }
  }
}
