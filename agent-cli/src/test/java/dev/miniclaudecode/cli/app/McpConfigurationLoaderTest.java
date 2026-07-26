package dev.miniclaudecode.cli.app;

import dev.miniclaudecode.cli.app.McpConfigurationLoader.ConfiguredServer;
import dev.miniclaudecode.extensions.mcp.McpServerConfig.Transport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigurationLoaderTest {
  @TempDir Path temporaryDirectory;

  @Test
  void loadsStdioAndStreamableHttpOnlyFromExplicitUserConfiguration() throws Exception {
    Path config = this.temporaryDirectory.resolve("config.yaml");
    Files.writeString(
        config,
        "mcp:\n"
            + "  servers:\n"
            + "    local:\n"
            + "      transport: stdio\n"
            + "      command: [java, -jar, server.jar]\n"
            + "      launch-approved: true\n"
            + "    remote:\n"
            + "      transport: streamable-http\n"
            + "      url: https://example.test/mcp\n");
    List<ConfiguredServer> servers = new McpConfigurationLoader().load(config);
    Assertions.assertThat(servers).hasSize(2);
    Assertions.assertThat(servers)
        .anySatisfy(
            value -> {
              Assertions.assertThat(value.config().transport()).isEqualTo(Transport.STDIO);
              Assertions.assertThat(value.launchApproved()).isTrue();
            })
        .anySatisfy(
            value ->
                Assertions.assertThat(value.config().transport())
                    .isEqualTo(Transport.STREAMABLE_HTTP));
  }
}
