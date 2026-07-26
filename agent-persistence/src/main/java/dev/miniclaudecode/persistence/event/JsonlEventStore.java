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
import java.util.List;
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
    Path file = this.eventFile(event.sessionId());
    ReentrantLock processLock =
        PROCESS_LOCKS.computeIfAbsent(file, ignoredx -> new ReentrantLock());
    processLock.lock();

    try {
      Files.createDirectories(this.eventRoot);
      byte[] bytes = (this.codec.encode(event) + "\n").getBytes(StandardCharsets.UTF_8);

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
      throw new UncheckedIOException("cannot append session event: " + file, var20);
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
