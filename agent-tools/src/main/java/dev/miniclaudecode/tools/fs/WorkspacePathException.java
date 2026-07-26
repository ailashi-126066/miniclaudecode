package dev.miniclaudecode.tools.fs;

public final class WorkspacePathException extends IllegalArgumentException {
  public WorkspacePathException(String message) {
    super(message);
  }

  public WorkspacePathException(String message, Throwable cause) {
    super(message, cause);
  }
}
