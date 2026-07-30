package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.session.SessionId;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.persistence.config.SecretRedactor;
import dev.miniclaudecode.persistence.event.JsonlEventStore;
import dev.miniclaudecode.persistence.path.UserDataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Read-only retrieval of exact cross-session history from the existing event ledger. */
final class SessionSearchTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "session",
          "search",
          "Search user requests and final answers in this workspace's prior session history",
          "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}},\"required\":[\"query\"]}",
          RiskLevel.LOW);
  private final Path eventRoot;
  private final JsonlEventStore events;

  SessionSearchTool(Path workspace, UserDataLayout layout, Set<String> secrets) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(layout, "layout must not be null");
    this.eventRoot = layout.sessionWorkspaceRoot(workspace).resolve("events");
    this.events = new JsonlEventStore(this.eventRoot, new SecretRedactor(), Set.copyOf(secrets));
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public java.util.concurrent.CompletionStage<ToolResult> execute(
      ToolCall call, ToolContext context) {
    try {
      JsonNode arguments = JSON.readTree(call.argumentsJson());
      String query = arguments.path("query").asText("").strip();
      int limit = arguments.path("limit").isMissingNode() ? 5 : arguments.path("limit").asInt();
      if (query.isBlank() || limit < 1 || limit > 20) {
        throw new IllegalArgumentException(
            "query must be non-blank and limit must be between 1 and 20");
      }
      List<Match> matches = search(query, limit);
      String output =
          matches.stream()
              .map(
                  match ->
                      "session="
                          + match.session()
                          + " turn="
                          + match.turn()
                          + " "
                          + match.kind()
                          + ": "
                          + match.text())
              .reduce((left, right) -> left + "\n\n" + right)
              .orElse("No matching session history.");
      return java.util.concurrent.CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              output,
              Optional.empty(),
              Map.of("matches", matches.size())));
    } catch (IOException | RuntimeException error) {
      return java.util.concurrent.CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              "session search failed: "
                  + Objects.requireNonNullElse(
                      error.getMessage(), error.getClass().getSimpleName()),
              Optional.empty(),
              Map.of()));
    }
  }

  private List<Match> search(String query, int limit) throws IOException {
    if (!Files.isDirectory(this.eventRoot)) {
      return List.of();
    }
    String normalizedQuery = query.toLowerCase(Locale.ROOT);
    try (Stream<Path> files = Files.list(this.eventRoot)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
          .sorted(Comparator.comparing(SessionSearchTool::modifiedAt).reversed())
          .flatMap(this::events)
          .filter(match -> match.text().toLowerCase(Locale.ROOT).contains(normalizedQuery))
          .limit(limit)
          .toList();
    }
  }

  private Stream<Match> events(Path file) {
    String name = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
    try {
      SessionId session = SessionId.of(name);
      return this.events.read(session).events().stream()
          .filter(
              event ->
                  event.type() == AgentEventType.USER_MESSAGE
                      || event.type() == AgentEventType.TURN_FINAL)
          .map(event -> match(session, event))
          .flatMap(Optional::stream);
    } catch (RuntimeException error) {
      return Stream.empty();
    }
  }

  private static Optional<Match> match(SessionId session, AgentEvent event) {
    Object text =
        event.payload().get(event.type() == AgentEventType.USER_MESSAGE ? "text" : "text");
    if (!(text instanceof String value) || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new Match(
            session.value(),
            event.turnId().value(),
            event.type() == AgentEventType.USER_MESSAGE ? "request" : "final",
            abbreviate(value)));
  }

  private static java.time.Instant modifiedAt(Path file) {
    try {
      return Files.getLastModifiedTime(file).toInstant();
    } catch (IOException error) {
      return java.time.Instant.EPOCH;
    }
  }

  private static String abbreviate(String text) {
    String normalized = text.replaceAll("\\s+", " ").strip();
    return normalized.length() <= 600 ? normalized : normalized.substring(0, 600) + "...";
  }

  private record Match(String session, long turn, String kind, String text) {}
}
