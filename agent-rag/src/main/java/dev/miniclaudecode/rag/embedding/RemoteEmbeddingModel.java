package dev.miniclaudecode.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Embeddings from an OpenAI-compatible {@code /v1/embeddings} endpoint.
 *
 * <p>Deliberately built on {@code java.net.http} and Jackson only — both already on this module's
 * classpath — so enabling remote embeddings adds no dependency that could fail to resolve. The
 * configured dimension is declared up front (the Lucene vector field needs it before the first
 * request) and every response is validated against it, because a silently different dimension would
 * poison the index rather than fail one call.
 *
 * <p>Embeds one text per request: the index embeds chunks one at a time, so a batch API would not
 * be exercised. If indexing throughput over a remote endpoint ever matters, batch at the caller.
 */
public final class RemoteEmbeddingModel implements EmbeddingModel, EmbeddingIdentity {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient client;
  private final URI endpoint;
  private final Optional<String> apiKey;
  private final String model;
  private final int dimensions;
  private final Duration timeout;

  public RemoteEmbeddingModel(
      URI baseUrl, Optional<String> apiKey, String model, int dimensions, Duration timeout) {
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    this.model = Objects.requireNonNull(model, "model must not be null").trim();
    if (this.model.isEmpty()) {
      throw new IllegalArgumentException("model must not be blank");
    }
    if (dimensions < 1) {
      throw new IllegalArgumentException("dimensions must be positive");
    }
    this.dimensions = dimensions;
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    this.endpoint = endpoint(baseUrl);
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  private static URI endpoint(URI baseUrl) {
    String base = baseUrl.toString();
    return URI.create(base.endsWith("/") ? base + "embeddings" : base + "/embeddings");
  }

  @Override
  public Response<Embedding> embed(String text) {
    try {
      String body =
          JSON.writeValueAsString(
              Map.of("model", this.model, "input", List.of(text == null ? "" : text)));
      HttpRequest.Builder request =
          HttpRequest.newBuilder(this.endpoint)
              .timeout(this.timeout)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      this.apiKey.ifPresent(key -> request.header("Authorization", "Bearer " + key));
      HttpResponse<String> response =
          this.client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException(
            "embedding request failed: HTTP "
                + response.statusCode()
                + " from "
                + this.endpoint.getHost()
                + ": "
                + truncate(response.body()));
      }
      return Response.from(Embedding.from(parseVector(response.body())));
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "embedding request failed: " + this.endpoint.getHost(), exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("embedding request interrupted", exception);
    }
  }

  private float[] parseVector(String responseBody) throws IOException {
    JsonNode data = JSON.readTree(responseBody).path("data");
    if (!data.isArray() || data.isEmpty()) {
      throw new IllegalStateException("embedding response has no data entries");
    }
    JsonNode values = data.get(0).path("embedding");
    if (!values.isArray()) {
      throw new IllegalStateException("embedding response is missing the embedding array");
    }
    float[] vector = new float[values.size()];
    for (int index = 0; index < vector.length; index++) {
      vector[index] = (float) values.get(index).asDouble();
    }
    if (vector.length != this.dimensions) {
      throw new IllegalStateException(
          "embedding dimension mismatch: rag.embedding.dimensions is "
              + this.dimensions
              + " but "
              + this.model
              + " returned "
              + vector.length
              + "; fix the configured dimensions");
    }
    return vector;
  }

  @Override
  public int dimension() {
    return this.dimensions;
  }

  @Override
  public String embeddingIdentity() {
    return "remote/" + this.model + "/" + this.dimensions;
  }

  private static String truncate(String value) {
    String text = value == null ? "" : value.strip();
    return text.length() <= 200 ? text : text.substring(0, 200);
  }
}
