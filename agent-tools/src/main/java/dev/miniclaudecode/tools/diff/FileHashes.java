package dev.miniclaudecode.tools.diff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FileHashes {
  public static final String MISSING = "missing";

  private FileHashes() {}

  public static String hash(Path path) {
    if (!Files.exists(path)) {
      return "missing";
    } else {
      try {
        return sha256(Files.readAllBytes(path));
      } catch (IOException var2) {
        throw new IllegalArgumentException("failed to hash file", var2);
      }
    }
  }

  public static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException var2) {
      throw new IllegalStateException("SHA-256 is unavailable", var2);
    }
  }
}
