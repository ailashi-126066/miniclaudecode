package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.AgentTool;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Lead-facing team, task assignment, and structured mailbox operations. */
final class TeamControlTool implements AgentTool {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "agent",
          "team",
          "Create and manage a persistent Agent team, tasks, members, and mailbox",
          """
          {"type":"object","properties":{
            "action":{"type":"string","enum":["create","join","assign","message","status","inbox","stop","archive"]},
            "teamId":{"type":"string"},"name":{"type":"string"},"memberId":{"type":"string"},
            "role":{"type":"string"},"writer":{"type":"boolean"},"task":{"type":"string"},
            "context":{"type":"string"},"sender":{"type":"string"},"recipient":{"type":"string"},
            "taskId":{"type":"string"},"messageType":{"type":"string"},"body":{"type":"string"}
          },"required":["action"]}
          """,
          RiskLevel.LOW,
          ToolEffect.PROCESS);

  private final TeamManager teams;

  TeamControlTool(TeamManager teams) {
    this.teams = Objects.requireNonNull(teams);
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
    try {
      JsonNode root = JSON.readTree(call.argumentsJson());
      String action = required(root, "action").toLowerCase(java.util.Locale.ROOT);
      String output;
      String teamId = root.path("teamId").asText("");
      switch (action) {
        case "create" -> {
          teamId = teams.create(required(root, "name"), context);
          output = "Team created: " + teamId;
        }
        case "join" ->
            output =
                "Member joined: "
                    + teams.join(
                        required(root, "teamId"),
                        required(root, "memberId"),
                        root.path("role").asText("member"),
                        root.path("writer").asBoolean(false),
                        context);
        case "assign" ->
            output =
                "Task assigned: "
                    + teams
                        .assign(
                            required(root, "teamId"),
                            required(root, "memberId"),
                            required(root, "task"),
                            root.path("context").asText(""),
                            context)
                        .id();
        case "message" ->
            output =
                "Message sent: "
                    + teams
                        .message(
                            required(root, "teamId"),
                            required(root, "sender"),
                            required(root, "recipient"),
                            root.path("taskId").asText(""),
                            root.path("messageType").asText("info"),
                            required(root, "body"),
                            context)
                        .id();
        case "status" -> output = render(teams.status(required(root, "teamId")));
        case "inbox" ->
            output =
                teams.inbox(required(root, "teamId"), required(root, "recipient")).stream()
                    .map(value -> value.id() + " " + value.sender() + ": " + value.body())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("(empty inbox)");
        case "stop" -> output = render(teams.stop(required(root, "teamId"), context));
        case "archive" -> output = render(teams.archive(required(root, "teamId"), context));
        default -> throw new IllegalArgumentException("unknown team action: " + action);
      }
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.COMPLETED,
              output,
              Optional.empty(),
              Map.of("teamId", teamId, "teamTasks", List.of(output))));
    } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException failure) {
      return CompletableFuture.completedFuture(
          new ToolResult(
              call.toolCallId(),
              ToolResult.Status.FAILED,
              "team operation failed: "
                  + Objects.requireNonNullElse(
                      failure.getMessage(), failure.getClass().getSimpleName()),
              Optional.empty(),
              Map.of()));
    }
  }

  private static String render(TeamManager.TeamSnapshot team) {
    StringBuilder output = new StringBuilder(team.id() + " [" + team.status() + "] " + team.name());
    team.members()
        .forEach(
            member ->
                output
                    .append("\nmember ")
                    .append(member.id())
                    .append(" [")
                    .append(member.status())
                    .append("] role=")
                    .append(member.role())
                    .append(member.writer() ? " writer" : " read-only"));
    team.tasks()
        .forEach(
            task ->
                output
                    .append("\ntask ")
                    .append(task.id())
                    .append(" [")
                    .append(task.status())
                    .append("] member=")
                    .append(task.memberId())
                    .append(" bg=")
                    .append(task.backgroundTaskId()));
    output.append("\nmailbox messages=").append(team.mailbox().size());
    return output.toString();
  }

  private static String required(JsonNode root, String field) {
    String value = root.path(field).asText("").strip();
    if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
