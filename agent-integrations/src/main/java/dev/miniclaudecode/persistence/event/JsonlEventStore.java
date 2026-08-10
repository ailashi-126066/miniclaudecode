package dev.miniclaudecode.persistence.event;

import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.session.SessionEventStore;
import dev.miniclaudecode.domain.session.SessionEventStore.ReadResult;
import dev.miniclaudecode.domain.session.SessionId;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public final class JsonlEventStore implements SessionEventStore {
  private static final Pattern SAFE_SESSION_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS =
      new ConcurrentHashMap<>();
  private final Path eventRoot;
  private final EventJsonCodec codec;

  public JsonlEventStore(Path eventRoot, SecretRedactor redactor, Set<String> knownSecrets) {
    this(eventRoot, new EventJsonCodec(redactor, knownSecrets));
  }

  public JsonlEventStore(Path eventRoot, EventJsonCodec codec) {
    this.eventRoot =
        Objects.requireNonNull(eventRoot, "eventRoot must not be null")
            .toAbsolutePath()
            .normalize();
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  public void append(AgentEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    this.appendAll(List.of(event));
  }

  /**
   * Appends a batch of events with one open + lock + write + fsync per session file, instead of one
   * per event. A 2000-token streamed answer used to cost 2000 fsyncs; batched by the audit layer it
   * costs a handful. Lines are encoded before the file is touched, so a codec failure writes
   * nothing, and a crash mid-write loses at most the incomplete last line — which {@code read}'s
   * tail recovery already discards.
   *
   * <p>Batches spanning several sessions commit per file in first-seen order and are NOT atomic
   * across files: a failure on the second file leaves the first durably written. Per-session order
   * within a batch is always preserved. (Production currently routes single events through {@link
   * #append}; the multi-session path exists for callers that batch at a higher level.)
   */
  public void appendAll(List<AgentEvent> events) {
    Objects.requireNonNull(events, "events must not be null");
    if (events.isEmpty()) {
      return;
    }
    Map<Path, StringBuilder> batches = new LinkedHashMap<>();
    for (AgentEvent event : events) {
      Objects.requireNonNull(event, "event must not be null");
      batches
          .computeIfAbsent(this.eventFile(event.sessionId()), ignored -> new StringBuilder())
          .append(this.codec.encode(event))
          .append('\n');
    }
    batches.forEach(this::writeBatch);
  }

  private void writeBatch(Path file, StringBuilder lines) {
    ReentrantLock processLock =
        PROCESS_LOCKS.computeIfAbsent(file, ignoredx -> new ReentrantLock());
    processLock.lock();

    try {
      Files.createDirectories(this.eventRoot);
      byte[] bytes = lines.toString().getBytes(StandardCharsets.UTF_8);

      try (FileChannel channel =
              FileChannel.open(
                  file,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.APPEND);
          FileLock ignored = channel.lock(); ) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }

        channel.force(false);
      }
    } catch (IOException var20) {
      throw new UncheckedIOException("cannot append session events: " + file, var20);
    } finally {
      processLock.unlock();
    }
  }

  public ReadResult read(SessionId sessionId) {
    Path file = this.eventFile(sessionId);
    if (!Files.isRegularFile(file)) {
      return new ReadResult(List.of(), List.of());
    } else {
      try {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return this.decodeLines(sessionId, content);
      } catch (IOException var4) {
        throw new UncheckedIOException("cannot read session events: " + file, var4);
      }
    }
  }

  private ReadResult decodeLines(SessionId expectedSession, String content) {
    List<AgentEvent> events = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    boolean hasIncompleteTail = !content.isEmpty() && !content.endsWith("\n");
    String[] lines = content.split("\n", -1);
    int completeLineCount = hasIncompleteTail ? lines.length - 1 : lines.length;

    for (int index = 0; index < completeLineCount; index++) {
      int lineNumber = index + 1;
      String line = lines[index].stripTrailing();
      if (!line.isBlank()) {
        try {
          EventJsonCodec.DecodeResult decoded = this.codec.decode(line);
          decoded
              .warning()
              .ifPresent(warning -> warnings.add("line " + lineNumber + ": " + warning));
          decoded
              .event()
              .ifPresent(
                  event -> {
                    if (event.sessionId().equals(expectedSession)) {
                      events.add(event);
                    } else {
                      warnings.add("line " + lineNumber + ": session id mismatch");
                    }
                  });
        } catch (IllegalArgumentException var12) {
          warnings.add("line " + lineNumber + ": malformed event skipped");
        }
      }
    }

    if (hasIncompleteTail) {
      warnings.add("incomplete tail ignored");
    }

    return new ReadResult(events, warnings);
  }

  private Path eventFile(SessionId sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (!SAFE_SESSION_ID.matcher(sessionId.value()).matches()) {
      throw new IllegalArgumentException("sessionId contains unsafe filename characters");
    } else {
      return this.eventRoot.resolve(sessionId.value() + ".jsonl");
    }
  }
}
