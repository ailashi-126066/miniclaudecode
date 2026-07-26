package dev.miniclaudecode.tools.result;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ToolResultStore {
  private static final Pattern REFERENCE = Pattern.compile("sha256:[0-9a-f]{64}");
  private final Path root;

  public ToolResultStore(Path root) {
    Objects.requireNonNull(root, "root must not be null");

    try {
      Files.createDirectories(root);
      this.root = root.toRealPath();
    } catch (IOException var3) {
      throw new IllegalArgumentException("tool result directory cannot be created", var3);
    }
  }

  public String put(String content) {
    Objects.requireNonNull(content, "content must not be null");
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String hash = sha256(bytes);
    String reference = "sha256:" + hash;
    Path target = this.root.resolve(hash + ".txt");
    if (Files.exists(target)) {
      return reference;
    } else {
      Path temporary = null;

      String var8;
      try {
        temporary = Files.createTempFile(this.root, ".result-", ".tmp");
        Files.write(temporary, bytes);

        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException var19) {
          Files.move(temporary, target);
        }

        return reference;
      } catch (IOException var20) {
        if (!Files.exists(target)) {
          throw new IllegalStateException("failed to persist tool result", var20);
        }

        var8 = reference;
      } finally {
        if (temporary != null) {
          try {
            Files.deleteIfExists(temporary);
          } catch (IOException var18) {
          }
        }
      }

      return var8;
    }
  }

  public String read(String reference) {
    String normalized = validateReference(reference);
    Path target = this.root.resolve(normalized.substring("sha256:".length()) + ".txt");

    try {
      return Files.readString(target, StandardCharsets.UTF_8);
    } catch (IOException var5) {
      throw new IllegalArgumentException("unknown tool result reference: " + normalized, var5);
    }
  }

  private static String validateReference(String reference) {
    if (reference != null && REFERENCE.matcher(reference).matches()) {
      return reference;
    } else {
      throw new IllegalArgumentException("invalid tool result reference");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException var2) {
      throw new IllegalStateException("SHA-256 is unavailable", var2);
    }
  }
}
