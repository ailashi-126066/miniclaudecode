package dev.miniclaudecode.tools.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Small OpenAI-compatible non-streaming client, enabled exclusively by environment configuration.
 */
public final class RemoteAiGateway {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static volatile RemoteAiGateway configuredDefault;
  private final URI endpoint;
  private final String key;
  private final String model;

  private RemoteAiGateway(URI endpoint, String key, String model) {
    this.endpoint = endpoint;
    this.key = key;
    this.model = model;
  }

  public static Optional<RemoteAiGateway> fromEnvironment() {
    String base = System.getenv("MINICLAUDE_AI_BASE_URL");
    String model = System.getenv("MINICLAUDE_AI_MODEL");
    if (base == null || base.isBlank() || model == null || model.isBlank()) {
      return Optional.ofNullable(configuredDefault);
    }
    return Optional.of(
        new RemoteAiGateway(
            URI.create(base.replaceAll("/+$", "") + "/chat/completions"),
            System.getenv("MINICLAUDE_AI_API_KEY"),
            model));
  }

  /** Configures the workspace's active OpenAI-compatible or Ollama provider as the fallback. */
  public static void configureDefault(URI baseUrl, Optional<String> apiKey, String model) {
    if (baseUrl == null || model == null || model.isBlank()) {
      configuredDefault = null;
      return;
    }
    configuredDefault =
        new RemoteAiGateway(
            URI.create(baseUrl.toString().replaceAll("/+$", "") + "/chat/completions"),
            apiKey.orElse(null),
            model);
  }

  public Optional<String> complete(String system, String user) {
    try {
      String body =
          JSON.writeValueAsString(
              java.util.Map.of(
                  "model",
                  model,
                  "temperature",
                  0,
                  "messages",
                  java.util.List.of(
                      java.util.Map.of("role", "system", "content", system),
                      java.util.Map.of("role", "user", "content", user))));
      HttpRequest.Builder request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (key != null && !key.isBlank()) request.header("Authorization", "Bearer " + key);
      JsonNode response =
          JSON.readTree(
              HttpClient.newHttpClient()
                  .send(request.build(), HttpResponse.BodyHandlers.ofString())
                  .body());
      return Optional.ofNullable(response.at("/choices/0/message/content").asText())
          .filter(value -> !value.isBlank());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (IOException ignored) {
      return Optional.empty();
    }
  }
}
