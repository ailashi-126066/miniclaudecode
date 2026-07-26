package dev.miniclaudecode.tools.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class WorkspacePathResolver {
  private final Path workspace;

  public WorkspacePathResolver(Path workspace) {
    Objects.requireNonNull(workspace, "workspace must not be null");

    try {
      this.workspace = workspace.toRealPath();
    } catch (IOException var3) {
      throw new WorkspacePathException("workspace does not exist or cannot be read", var3);
    }

    if (!Files.isDirectory(this.workspace, LinkOption.NOFOLLOW_LINKS)) {
      throw new WorkspacePathException("workspace must be a directory");
    }
  }

  public Path workspace() {
    return this.workspace;
  }

  public Path resolveExisting(String requestedPath) {
    String value = requestedPath != null && !requestedPath.isBlank() ? requestedPath.trim() : ".";
    Path lexicalTarget = this.resolveLexically(value);

    Path realTarget;
    try {
      realTarget = lexicalTarget.toRealPath();
    } catch (IOException var6) {
      throw new WorkspacePathException("workspace path does not exist: " + value, var6);
    }

    if (!realTarget.startsWith(this.workspace)) {
      throw new WorkspacePathException("symbolic link resolves outside the workspace");
    } else {
      return realTarget;
    }
  }

  public Path resolveForWrite(String requestedPath) {
    if (requestedPath != null && !requestedPath.isBlank()) {
      String value = requestedPath.trim();
      Path lexicalTarget = this.resolveLexically(value);
      if (Files.exists(lexicalTarget, LinkOption.NOFOLLOW_LINKS)) {
        return this.resolveExisting(value);
      } else {
        Path parent = lexicalTarget.getParent();
        if (parent == null) {
          throw new WorkspacePathException("write path must have a workspace parent");
        } else {
          Path realParent;
          try {
            realParent = parent.toRealPath();
          } catch (IOException var7) {
            throw new WorkspacePathException("write parent directory does not exist", var7);
          }

          if (!realParent.startsWith(this.workspace)) {
            throw new WorkspacePathException("symbolic link parent resolves outside the workspace");
          } else {
            return realParent.resolve(lexicalTarget.getFileName()).normalize();
          }
        }
      }
    } else {
      throw new WorkspacePathException("write path must not be blank");
    }
  }

  public String relativeDisplay(Path path) {
    Objects.requireNonNull(path, "path must not be null");
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(this.workspace)) {
      throw new WorkspacePathException("path is outside the workspace");
    } else {
      String display = this.workspace.relativize(normalized).toString().replace('\\', '/');
      return display.isEmpty() ? "." : display;
    }
  }

  private Path resolveLexically(String value) {
    Path relative;
    try {
      relative = Path.of(value);
    } catch (InvalidPathException var4) {
      throw new WorkspacePathException("invalid workspace path", var4);
    }

    if (relative.isAbsolute()) {
      throw new WorkspacePathException("workspace path must be relative");
    } else {
      Path lexicalTarget = this.workspace.resolve(relative).normalize();
      if (!lexicalTarget.startsWith(this.workspace)) {
        throw new WorkspacePathException("path resolves outside the workspace");
      } else {
        return lexicalTarget;
      }
    }
  }
}
