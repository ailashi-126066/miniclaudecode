package dev.miniclaudecode.persistence.checkpoint;

import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.runtime.AgentCheckpointStore;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Atomic Java-serialization checkpoint store for paused explicit AgentLoop state. */
public final class FileCheckpointSaver<State> implements AgentCheckpointStore {
  private final Path root;
  private final Function<Map<String, Object>, State> stateFactory;

  public FileCheckpointSaver(Path root, Function<Map<String, Object>, State> stateFactory) {
    this.root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
    this.stateFactory = Objects.requireNonNull(stateFactory, "stateFactory must not be null");
    try {
      Files.createDirectories(this.root);
    } catch (IOException failure) {
      throw new IllegalArgumentException("checkpoint directory cannot be created", failure);
    }
  }

  @Override
  public Optional<Map<String, Object>> load(SessionId sessionId) {
    Path file = file(sessionId);
    if (!Files.isRegularFile(file)) return Optional.empty();
    try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(file))) {
      Object value = input.readObject();
      if (!(value instanceof Map<?, ?> raw)) {
        throw new IllegalStateException("checkpoint does not contain state data");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> state = (Map<String, Object>) raw;
      return Optional.of(Map.copyOf(state));
    } catch (IOException | ClassNotFoundException failure) {
      throw new IllegalStateException("cannot read agent checkpoint", failure);
    }
  }

  public Optional<State> get(SessionId sessionId) {
    return load(sessionId).map(stateFactory);
  }

  @Override
  public void save(SessionId sessionId, Map<String, Object> state) {
    Path target = file(sessionId);
    Path temporary = null;
    try {
      temporary = Files.createTempFile(root, ".checkpoint-", ".tmp");
      try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
        output.writeObject(Map.copyOf(state));
      }
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot persist agent checkpoint", failure);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
      }
    }
  }

  @Override
  public void release(SessionId sessionId) {
    try {
      Files.deleteIfExists(file(sessionId));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot release agent checkpoint", failure);
    }
  }

  private Path file(SessionId sessionId) {
    String value = Objects.requireNonNull(sessionId).value();
    return root.resolve(sha256(value) + ".checkpoint");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
