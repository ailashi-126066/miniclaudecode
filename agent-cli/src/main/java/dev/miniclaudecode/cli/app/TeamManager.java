package dev.miniclaudecode.cli.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.cli.app.BackgroundAgentManager.Mode;
import dev.miniclaudecode.cli.app.BackgroundAgentManager.TaskSpec;
import dev.miniclaudecode.domain.event.AgentEvent;
import dev.miniclaudecode.domain.event.AgentEventType;
import dev.miniclaudecode.domain.tool.AgentTool.ToolContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Persistent in-process lead/member task store and structured mailbox. */
final class TeamManager {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final BackgroundAgentManager background;
  private final Path storeFile;
  private final Clock clock;
  private final Map<String, TeamData> teams = new LinkedHashMap<>();

  TeamManager(BackgroundAgentManager background, Path storeFile, Clock clock) {
    this.background = Objects.requireNonNull(background);
    this.storeFile = Objects.requireNonNull(storeFile).toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock);
    restore();
  }

  synchronized String create(String name, ToolContext lead) {
    String id = "team-" + UUID.randomUUID().toString().substring(0, 10);
    TeamData team = new TeamData(id, text(name, "name"), lead.sessionId().value(), "ACTIVE");
    team.members.put(
        "lead", new TeamMember("lead", lead.sessionId().value(), "lead", false, "ACTIVE"));
    teams.put(id, team);
    persist();
    emit(lead, AgentEventType.TEAM_UPDATED, id, "created");
    return id;
  }

  synchronized String join(
      String teamId, String memberId, String role, boolean writer, ToolContext context) {
    TeamData team = require(teamId);
    ensureLead(team, context);
    ensureActive(team);
    String id = text(memberId, "memberId");
    if (team.members.containsKey(id))
      throw new IllegalArgumentException("member already exists: " + id);
    team.members.put(
        id, new TeamMember(id, context.sessionId().value(), text(role, "role"), writer, "IDLE"));
    persist();
    emit(context, AgentEventType.TEAM_UPDATED, team.id, "member joined: " + id);
    return id;
  }

  synchronized TeamTask assign(
      String teamId, String memberId, String description, String forkContext, ToolContext context) {
    TeamData team = require(teamId);
    ensureLead(team, context);
    ensureActive(team);
    TeamMember member = team.members.get(memberId);
    if (member == null) throw new IllegalArgumentException("unknown team member: " + memberId);
    String taskId = "task-" + UUID.randomUUID().toString().substring(0, 10);
    String backgroundId =
        background.start(
            new TaskSpec(
                text(description, "description"),
                member.writer ? "implement" : "explore",
                6,
                Mode.FORK,
                Objects.requireNonNullElse(forkContext, ""),
                0),
            context);
    TeamTask task =
        new TeamTask(
            taskId,
            memberId,
            description,
            backgroundId,
            "RUNNING",
            Instant.now(clock).toString(),
            "");
    team.tasks.put(taskId, task);
    team.members.put(memberId, member.withStatus("RUNNING"));
    persist();
    emit(context, AgentEventType.TEAM_UPDATED, team.id, "assigned " + taskId + " to " + memberId);
    return task;
  }

  synchronized MailMessage message(
      String teamId,
      String sender,
      String recipient,
      String taskId,
      String type,
      String body,
      ToolContext context) {
    TeamData team = require(teamId);
    ensureLead(team, context);
    if (!team.members.containsKey(sender) || !team.members.containsKey(recipient)) {
      throw new IllegalArgumentException("sender and recipient must be team members");
    }
    if (taskId != null && !taskId.isBlank() && !team.tasks.containsKey(taskId)) {
      throw new IllegalArgumentException("unknown team task: " + taskId);
    }
    MailMessage message =
        new MailMessage(
            "msg-" + UUID.randomUUID().toString().substring(0, 10),
            sender,
            recipient,
            Objects.requireNonNullElse(taskId, ""),
            text(type, "type"),
            text(body, "body"),
            Instant.now(clock).toString());
    team.mailbox.add(message);
    persist();
    emit(
        context,
        AgentEventType.TEAM_MESSAGE,
        team.id,
        message.id + " " + sender + " -> " + recipient);
    return message;
  }

  synchronized TeamSnapshot status(String teamId) {
    TeamData team = require(teamId);
    refresh(team);
    persist();
    return snapshot(team);
  }

  synchronized List<MailMessage> inbox(String teamId, String recipient) {
    TeamData team = require(teamId);
    return team.mailbox.stream().filter(message -> message.recipient.equals(recipient)).toList();
  }

  synchronized TeamSnapshot stop(String teamId, ToolContext context) {
    TeamData team = require(teamId);
    ensureLead(team, context);
    team.tasks.replaceAll(
        (id, task) -> {
          if (!terminal(task.status)) background.cancel(task.backgroundTaskId);
          return task.withStatus("CANCELLED", "stopped by lead");
        });
    team.members.replaceAll((id, member) -> member.withStatus("STOPPED"));
    team.status = "STOPPED";
    persist();
    emit(context, AgentEventType.TEAM_UPDATED, team.id, "stopped");
    return snapshot(team);
  }

  synchronized TeamSnapshot archive(String teamId, ToolContext context) {
    TeamData team = require(teamId);
    ensureLead(team, context);
    refresh(team);
    if (team.tasks.values().stream().anyMatch(task -> !terminal(task.status))) {
      throw new IllegalStateException("stop or finish active team tasks before archive");
    }
    team.status = "ARCHIVED";
    team.members.replaceAll((id, member) -> member.withStatus("ARCHIVED"));
    persist();
    emit(context, AgentEventType.TEAM_UPDATED, team.id, "archived");
    return snapshot(team);
  }

  synchronized String renderAll() {
    if (teams.isEmpty()) return "(no teams)";
    teams.values().forEach(this::refresh);
    return teams.values().stream()
        .map(TeamManager::renderSummary)
        .reduce((left, right) -> left + System.lineSeparator() + right)
        .orElseThrow();
  }

  private static String renderSummary(TeamData team) {
    String members =
        team.members.values().stream()
            .map(member -> member.id + ":" + member.status)
            .reduce((left, right) -> left + "," + right)
            .orElse("none");
    String tasks =
        team.tasks.values().stream()
            .map(task -> task.id + ":" + task.status)
            .reduce((left, right) -> left + "," + right)
            .orElse("none");
    long results = team.tasks.values().stream().filter(task -> !task.result.isBlank()).count();
    return team.id
        + " ["
        + team.status
        + "] "
        + team.name
        + " members="
        + members
        + " tasks="
        + tasks
        + " messages="
        + team.mailbox.size()
        + " results="
        + results;
  }

  private void refresh(TeamData team) {
    for (Map.Entry<String, TeamTask> entry : new ArrayList<>(team.tasks.entrySet())) {
      TeamTask task = entry.getValue();
      BackgroundAgentManager.TaskSnapshot backgroundTask = background.status(task.backgroundTaskId);
      String status = backgroundTask.status().name();
      team.tasks.put(entry.getKey(), task.withStatus(status, backgroundTask.resultSummary()));
      TeamMember member = team.members.get(task.memberId);
      if (member != null) team.members.put(member.id, member.withStatus(status));
    }
  }

  private TeamSnapshot snapshot(TeamData team) {
    return new TeamSnapshot(
        team.id,
        team.name,
        team.leadSession,
        team.status,
        List.copyOf(team.members.values()),
        List.copyOf(team.tasks.values()),
        List.copyOf(team.mailbox));
  }

  private TeamData require(String id) {
    TeamData team = teams.get(text(id, "teamId"));
    if (team == null) throw new IllegalArgumentException("unknown team: " + id);
    return team;
  }

  private static void ensureActive(TeamData team) {
    if (!"ACTIVE".equals(team.status))
      throw new IllegalStateException("team is not active: " + team.id);
  }

  private static void ensureLead(TeamData team, ToolContext context) {
    if (!team.leadSession.equals(context.sessionId().value())) {
      throw new SecurityException("only the team lead session may mutate this team");
    }
  }

  private static boolean terminal(String status) {
    return List.of("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").contains(status);
  }

  private void emit(ToolContext context, AgentEventType type, String teamId, String summary) {
    context
        .eventSink()
        .emit(
            AgentEvent.create(
                context.sessionId(),
                context.turnId(),
                type,
                Map.of("teamId", teamId, "summary", summary),
                clock));
  }

  private synchronized void persist() {
    try {
      Path parent = Objects.requireNonNull(storeFile.getParent(), "store file must have a parent");
      Files.createDirectories(parent);
      List<Map<String, Object>> values = teams.values().stream().map(TeamManager::map).toList();
      Path temporary = Files.createTempFile(parent, ".teams-", ".tmp");
      Files.writeString(temporary, JSON.writeValueAsString(values), StandardCharsets.UTF_8);
      Files.move(temporary, storeFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot persist team state", failure);
    }
  }

  private void restore() {
    if (!Files.isRegularFile(storeFile)) return;
    try {
      List<Map<String, Object>> values =
          JSON.readValue(Files.readString(storeFile), new TypeReference<>() {});
      for (Map<String, Object> value : values) {
        TeamData team =
            new TeamData(
                String.valueOf(value.get("id")),
                String.valueOf(value.get("name")),
                String.valueOf(value.get("leadSession")),
                String.valueOf(value.get("status")));
        maps(value.get("members"))
            .forEach(
                member -> {
                  TeamMember parsed =
                      new TeamMember(
                          string(member, "id"),
                          string(member, "sessionId"),
                          string(member, "role"),
                          Boolean.parseBoolean(String.valueOf(member.get("writer"))),
                          string(member, "status"));
                  team.members.put(parsed.id, parsed);
                });
        maps(value.get("tasks"))
            .forEach(
                task -> {
                  TeamTask parsed =
                      new TeamTask(
                          string(task, "id"),
                          string(task, "memberId"),
                          string(task, "description"),
                          string(task, "backgroundTaskId"),
                          string(task, "status"),
                          string(task, "createdAt"),
                          string(task, "result"));
                  team.tasks.put(parsed.id, parsed);
                });
        maps(value.get("mailbox"))
            .forEach(
                message ->
                    team.mailbox.add(
                        new MailMessage(
                            string(message, "id"),
                            string(message, "sender"),
                            string(message, "recipient"),
                            string(message, "taskId"),
                            string(message, "type"),
                            string(message, "body"),
                            string(message, "sentAt"))));
        teams.put(team.id, team);
      }
    } catch (IOException | RuntimeException failure) {
      throw new IllegalStateException("cannot restore team state", failure);
    }
  }

  private static Map<String, Object> map(TeamData team) {
    return Map.of(
        "id", team.id,
        "name", team.name,
        "leadSession", team.leadSession,
        "status", team.status,
        "members", team.members.values(),
        "tasks", team.tasks.values(),
        "mailbox", team.mailbox);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream()
        .filter(Map.class::isInstance)
        .map(item -> (Map<String, Object>) item)
        .toList();
  }

  private static String string(Map<String, Object> map, String key) {
    return Objects.requireNonNullElse(String.valueOf(map.get(key)), "");
  }

  private static String text(String value, String field) {
    value = Objects.requireNonNullElse(value, "").strip();
    if (value.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }

  record TeamMember(String id, String sessionId, String role, boolean writer, String status) {
    TeamMember withStatus(String value) {
      return new TeamMember(id, sessionId, role, writer, value);
    }
  }

  record TeamTask(
      String id,
      String memberId,
      String description,
      String backgroundTaskId,
      String status,
      String createdAt,
      String result) {
    TeamTask withStatus(String value, String output) {
      return new TeamTask(id, memberId, description, backgroundTaskId, value, createdAt, output);
    }
  }

  record MailMessage(
      String id,
      String sender,
      String recipient,
      String taskId,
      String type,
      String body,
      String sentAt) {}

  record TeamSnapshot(
      String id,
      String name,
      String leadSession,
      String status,
      List<TeamMember> members,
      List<TeamTask> tasks,
      List<MailMessage> mailbox) {}

  private static final class TeamData {
    private final String id;
    private final String name;
    private final String leadSession;
    private String status;
    private final Map<String, TeamMember> members = new LinkedHashMap<>();
    private final Map<String, TeamTask> tasks = new LinkedHashMap<>();
    private final List<MailMessage> mailbox = new ArrayList<>();

    private TeamData(String id, String name, String leadSession, String status) {
      this.id = id;
      this.name = name;
      this.leadSession = leadSession;
      this.status = status;
    }
  }
}
