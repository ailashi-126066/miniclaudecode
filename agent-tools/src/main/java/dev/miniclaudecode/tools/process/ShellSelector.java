package dev.miniclaudecode.tools.process;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ShellSelector {
  private static final String WINDOWS_UTF8_PREFIX =
      "[Console]::InputEncoding = [Text.UTF8Encoding]::new($false); [Console]::OutputEncoding ="
          + " [Text.UTF8Encoding]::new($false); $OutputEncoding = [Text.UTF8Encoding]::new($false);"
          + " ";
  private final ShellSelector.Platform platform;

  public ShellSelector(ShellSelector.Platform platform) {
    this.platform = Objects.requireNonNull(platform, "platform must not be null");
  }

  public static ShellSelector system() {
    return forOsName(System.getProperty("os.name", ""));
  }

  public static ShellSelector forOsName(String osName) {
    Objects.requireNonNull(osName, "osName must not be null");
    ShellSelector.Platform platform =
        osName.toLowerCase(Locale.ROOT).contains("windows")
            ? ShellSelector.Platform.WINDOWS
            : ShellSelector.Platform.POSIX;
    return new ShellSelector(platform);
  }

  public ShellSelector.Platform platform() {
    return this.platform;
  }

  public List<String> command(String script) {
    if (script == null || script.isBlank()) {
      throw new IllegalArgumentException("script must not be blank");
    } else {
      return this.platform == ShellSelector.Platform.WINDOWS
          ? List.of(
              "powershell.exe",
              "-NoLogo",
              "-NoProfile",
              "-NonInteractive",
              "-ExecutionPolicy",
              "Bypass",
              "-Command",
              "[Console]::InputEncoding = [Text.UTF8Encoding]::new($false);"
                  + " [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); $OutputEncoding"
                  + " = [Text.UTF8Encoding]::new($false); "
                  + script)
          : List.of("/bin/sh", "-lc", script);
    }
  }

  public static enum Platform {
    WINDOWS,
    POSIX;
  }
}
