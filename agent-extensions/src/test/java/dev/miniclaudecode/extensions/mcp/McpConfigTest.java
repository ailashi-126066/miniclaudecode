package dev.miniclaudecode.extensions.mcp;

import dev.miniclaudecode.extensions.mcp.McpServerConfig.Transport;
import java.net.URI;
import java.util.List;
import org.assertj.core.api.AbstractThrowableAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class McpConfigTest {
  @Test
  void validatesStdioAndStreamableHttpWithoutLegacySseMode() {
    McpServerConfig stdio = McpServerConfig.stdio("github", List.of("npx", "server"));
    McpServerConfig http =
        McpServerConfig.streamableHttp("docs", URI.create("https://example.test/mcp"));
    Assertions.assertThat(stdio.transport()).isEqualTo(Transport.STDIO);
    Assertions.assertThat(stdio.namespace()).isEqualTo("mcp.github");
    Assertions.assertThat(http.transport()).isEqualTo(Transport.STREAMABLE_HTTP);
    Assertions.assertThat(Transport.values())
        .containsExactly(new Transport[] {Transport.STDIO, Transport.STREAMABLE_HTTP});
  }

  @Test
  void rejectsMissingCommandsAndUnsafeUrls() {
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(() -> McpServerConfig.stdio("empty", List.of()))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("command");
    ((AbstractThrowableAssert)
            Assertions.assertThatThrownBy(
                    () -> McpServerConfig.streamableHttp("file", URI.create("file:///tmp/server")))
                .isInstanceOf(IllegalArgumentException.class))
        .hasMessageContaining("http(s)");
  }
}
