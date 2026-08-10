package dev.miniclaudecode.persistence.config;

public record MemoryConfig(boolean enabled, String backend, boolean approvalRequired) {
  public MemoryConfig {
    backend = backend == null ? "" : backend.trim().toLowerCase(java.util.Locale.ROOT);
    if (!"sqlite".equals(backend)) {
      throw new IllegalArgumentException("memory.backend must be sqlite");
    }
    if (!approvalRequired) {
      throw new IllegalArgumentException("memory.approval-required must remain true");
    }
  }

  public static MemoryConfig defaults() {
    return new MemoryConfig(true, "sqlite", true);
  }
}
