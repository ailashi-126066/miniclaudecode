package dev.miniclaudecode.tools.fs;

import dev.miniclaudecode.tools.diff.FileHashes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ConcurrentModificationException;
import java.util.Objects;

public final class AtomicFileWriter {
  public void write(Path target, byte[] content, String expectedBeforeHash) {
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(content, "content must not be null");
    if (expectedBeforeHash != null && !expectedBeforeHash.isBlank()) {
      String currentHash = FileHashes.hash(target);
      if (!currentHash.equals(expectedBeforeHash)) {
        throw new ConcurrentModificationException("target changed after diff approval");
      } else {
        Path parent = target.getParent();
        if (parent == null) {
          throw new IllegalArgumentException("target must have a parent directory");
        } else {
          Path temporary = null;

          try {
            temporary = Files.createTempFile(parent, ".mini-claude-code-", ".tmp");

            try (FileChannel channel =
                FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
              ByteBuffer buffer = ByteBuffer.wrap(content);

              while (buffer.hasRemaining()) {
                channel.write(buffer);
              }

              channel.force(true);
            }

            try {
              Files.move(
                  temporary,
                  target,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException var21) {
              Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
          } catch (IOException var23) {
            throw new IllegalStateException("atomic file write failed", var23);
          } finally {
            if (temporary != null) {
              try {
                Files.deleteIfExists(temporary);
              } catch (IOException var19) {
              }
            }
          }
        }
      }
    } else {
      throw new IllegalArgumentException("expectedBeforeHash must not be blank");
    }
  }
}
