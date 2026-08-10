package dev.miniclaudecode.persistence.path;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class UserDataLayout {
  private static final String DIRECTORY_NAME = ".mini-claude-code";
  private final Path root;

  private UserDataLayout(Path userHome) {
    this.root =
        Objects.requireNonNull(userHome, "userHome must not be null")
            .toAbsolutePath()
            .normalize()
            .resolve(".mini-claude-code");
  }

  public static UserDataLayout forHome(Path userHome) {
    return new UserDataLayout(userHome);
  }

  public static UserDataLayout systemDefault() {
    return forHome(Path.of(System.getProperty("user.home")));
  }

  public Path root() {
    return this.root;
  }

  public Path configFile() {
    return this.root.resolve("config.yaml");
  }

  public Path permissionsFile() {
    return this.root.resolve("permissions.json");
  }

  public Path historyFile() {
    return this.root.resolve("history");
  }

  public Path sessionsRoot() {
    return this.root.resolve("sessions");
  }

  public Path checkpointsRoot() {
    return this.root.resolve("checkpoints");
  }

  public Path toolResultsRoot() {
    return this.root.resolve("tool-results");
  }

  public Path indexesRoot() {
    return this.root.resolve("indexes");
  }

  public Path memoryDatabase() {
    return this.root.resolve("memory").resolve("memory.db");
  }

  public Path globalMiniclaudeFile() {
    return this.root.resolve("global").resolve("miniclaude.md");
  }

  public Path skillsRoot() {
    return this.root.resolve("skills");
  }

  public Path sessionWorkspaceRoot(Path workspace) {
    return this.sessionsRoot().resolve(this.workspaceHash(workspace));
  }

  public Path indexWorkspaceRoot(Path workspace) {
    return this.indexesRoot().resolve(this.workspaceHash(workspace));
  }

  public String workspaceHash(Path workspace) {
    Path normalized =
        Objects.requireNonNull(workspace, "workspace must not be null")
            .toAbsolutePath()
            .normalize();
    String identity = normalized.toString();
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      identity = identity.toLowerCase(Locale.ROOT);
    }

    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException var5) {
      throw new IllegalStateException("SHA-256 is not available", var5);
    }
  }
}
