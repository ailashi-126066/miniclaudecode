package dev.miniclaudecode.rag.embedding;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.segment.TextSegment;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteEmbeddingModelTest {
  private HttpServer server;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private volatile String responseBody = "";
  private volatile int responseStatus = 200;

  @BeforeEach
  void startServer() throws IOException {
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.createContext(
        "/v1/embeddings",
        exchange -> {
          this.requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          this.authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = this.responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(this.responseStatus, bytes.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    this.server.start();
  }

  @AfterEach
  void stopServer() {
    this.server.stop(0);
  }

  private RemoteEmbeddingModel model(int dimensions) {
    URI base = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1");
    return new RemoteEmbeddingModel(
        base, Optional.of("sk-test"), "test-embed", dimensions, Duration.ofSeconds(5L));
  }

  @Test
  void postsInputWithBearerAuthAndParsesTheVector() {
    this.responseBody =
        "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,"
            + "0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,"
            + "0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,"
            + "0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8]}]}";
    float[] vector = this.model(32).embed("class App {}").content().vector();
    Assertions.assertThat(vector).hasSize(32);
    Assertions.assertThat(vector[0]).isEqualTo(0.1F);
    Assertions.assertThat(this.authorization.get()).isEqualTo("Bearer sk-test");
    Assertions.assertThat(this.requestBody.get())
        .contains("\"model\":\"test-embed\"")
        .contains("class App {}");
  }

  @Test
  void rejectsAVectorWhoseDimensionDiffersFromTheConfiguredOne() {
    this.responseBody = "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}";
    Assertions.assertThatThrownBy(() -> this.model(32).embed("text"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dimension mismatch")
        .hasMessageContaining("32")
        .hasMessageContaining("3");
  }

  @Test
  void reportsHttpFailuresWithStatusAndTruncatedBody() {
    this.responseStatus = 429;
    this.responseBody = "{\"error\":{\"message\":\"rate limited\"}}";
    Assertions.assertThatThrownBy(() -> this.model(32).embed("text"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 429")
        .hasMessageContaining("rate limited");
  }

  @Test
  void declaresItsIdentityFromEndpointModelAndDimensions() {
    int port = this.server.getAddress().getPort();
    Assertions.assertThat(this.model(32).embeddingIdentity())
        .isEqualTo("remote/127.0.0.1:" + port + "/test-embed/32");
    Assertions.assertThat(new LocalCodeEmbeddingModel(64).embeddingIdentity())
        .isEqualTo("local-hash/64");
  }

  @Test
  void batchesInputsAndRestoresTheirResponseOrder() {
    this.responseBody =
        "{\"data\":[{\"index\":1,\"embedding\":[0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,"
            + "0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2]},"
            + "{\"index\":0,\"embedding\":[0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1]}]}";

    var embeddings =
        this.model(32)
            .embedAll(java.util.List.of(TextSegment.from("first"), TextSegment.from("second")));

    Assertions.assertThat(embeddings.content()).hasSize(2);
    Assertions.assertThat(embeddings.content().getFirst().vector()[0]).isEqualTo(0.1F);
    Assertions.assertThat(embeddings.content().get(1).vector()[0]).isEqualTo(0.2F);
    Assertions.assertThat(this.requestBody.get()).contains("\"dimensions\":32", "first", "second");
  }
}
