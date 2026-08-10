package dev.miniclaudecode.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.miniclaudecode.domain.message.AgentMessage.SystemMessage;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.tool.ToolEffect;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Lightweight, tool-free planner backed by the active conversation model. */
public final class StructuredTaskPlanner implements TaskPlanner {
  private static final String SYSTEM_PROMPT =
      """
      You are the planning layer of a coding agent. Produce a small, executable plan as JSON only.
      Do not call tools and do not include markdown. Use at most 12 ordered steps. Every step must
      have a stable id, explicit dependencies, at least one observable acceptance criterion, and
      the tool effects it expects. Valid effects are READ_ONLY_LOCAL, READ_ONLY_EXTERNAL,
      USER_INTERACTION, MUTATION, PROCESS, EXTERNAL_EFFECT. Keep steps outcome-focused.
      Schema: {"goal":"...","steps":[{"id":"step-1","description":"...",
      "dependsOn":[],"acceptanceCriteria":["..."],"expectedEffects":["..."]}]}
      """;
  private static final String REPAIR_PROMPT =
      "Return only valid JSON matching the requested planning schema. Preserve the intended plan.";

  private final ModelClient modelClient;
  private final ObjectMapper mapper;
  private final Clock clock;

  public StructuredTaskPlanner(ModelClient modelClient, Clock clock) {
    this(modelClient, new ObjectMapper(), clock);
  }

  StructuredTaskPlanner(ModelClient modelClient, ObjectMapper mapper, Clock clock) {
    this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public CompletionStage<Plan> createPlan(PlanningInput input, ModelRequest parentRequest) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(parentRequest, "parentRequest must not be null");
    return call(
            parentRequest,
            List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(
                    render(input)
                        + "\n\nMaximum steps for this run: "
                        + maximumSteps(parentRequest))))
        .thenCompose(
            first -> {
              try {
                return CompletableFuture.completedFuture(parse(first, maximumSteps(parentRequest)));
              } catch (RuntimeException invalid) {
                return call(
                        parentRequest,
                        List.of(
                            new SystemMessage(SYSTEM_PROMPT),
                            new UserMessage(
                                REPAIR_PROMPT + "\n<invalid>\n" + first + "\n</invalid>")))
                    .thenApply(
                        repaired -> {
                          try {
                            return parse(repaired, maximumSteps(parentRequest));
                          } catch (RuntimeException stillInvalid) {
                            return fallback(input);
                          }
                        });
              }
            })
        .exceptionally(ignored -> fallback(input));
  }

  private CompletionStage<String> call(
      ModelRequest parent, List<dev.miniclaudecode.domain.message.AgentMessage> messages) {
    ModelRequest request =
        new ModelRequest(
            parent.providerProfile(),
            parent.modelName(),
            messages,
            List.of(),
            false,
            Math.min(parent.maxOutputTokens(), 2048),
            Map.of("planning", true, "requireVerification", false));
    CompletableFuture<String> result = new CompletableFuture<>();
    StringBuilder text = new StringBuilder();
    try {
      modelClient.stream(request)
          .subscribe(
              new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription value) {
                  subscription = value;
                  value.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ModelStreamEvent event) {
                  if (event instanceof ModelStreamEvent.TextDelta delta) {
                    text.append(delta.text());
                  } else if (event instanceof ModelStreamEvent.Failed failure) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException(failure.message()));
                  }
                }

                @Override
                public void onError(Throwable error) {
                  result.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                  if (!result.isDone()) {
                    result.complete(text.toString());
                  }
                }
              });
    } catch (RuntimeException error) {
      result.completeExceptionally(error);
    }
    return result;
  }

  private Plan parse(String raw, int maximumSteps) {
    try {
      JsonNode root = mapper.readTree(stripFence(raw));
      String goal = required(root, "goal");
      JsonNode rawSteps = root.path("steps");
      if (!rawSteps.isArray()) {
        throw new IllegalArgumentException("steps must be an array");
      }
      List<PlanStep> steps = new ArrayList<>();
      for (JsonNode node : rawSteps) {
        if (steps.size() >= maximumSteps) {
          break;
        }
        Set<ToolEffect> effects = new LinkedHashSet<>();
        node.path("expectedEffects")
            .forEach(value -> effects.add(ToolEffect.valueOf(value.asText())));
        steps.add(
            new PlanStep(
                required(node, "id"),
                required(node, "description"),
                strings(node.path("dependsOn")),
                strings(node.path("acceptanceCriteria")),
                effects,
                PlanStepStatus.PENDING,
                0,
                Optional.empty()));
      }
      Instant now = clock.instant();
      return new Plan(UUID.randomUUID(), goal, PlanStatus.DRAFT, 1, 0, steps, now, now);
    } catch (IOException | IllegalArgumentException error) {
      throw new IllegalArgumentException("invalid planner response", error);
    }
  }

  private static int maximumSteps(ModelRequest request) {
    Object configured = request.attributes().get("planningMaxSteps");
    int value = configured instanceof Number number ? number.intValue() : Plan.MAX_STEPS;
    return Math.max(1, Math.min(value, Plan.MAX_STEPS));
  }

  private Plan fallback(PlanningInput input) {
    Instant now = clock.instant();
    Set<ToolEffect> effects =
        input.requestedEffects().isEmpty()
            ? Set.of(ToolEffect.READ_ONLY_LOCAL)
            : input.requestedEffects();
    PlanStep step =
        new PlanStep(
            "step-1",
            input.goal(),
            List.of(),
            List.of("The requested outcome is complete and verified"),
            effects,
            PlanStepStatus.PENDING,
            0,
            Optional.empty());
    return new Plan(
        UUID.randomUUID(), input.goal(), PlanStatus.DRAFT, 1, 0, List.of(step), now, now);
  }

  private static String render(PlanningInput input) {
    return "Goal:\n"
        + input.goal()
        + "\n\nDiscovery context:\n"
        + input.discoveryContext()
        + "\n\nRelevant approved memories:\n"
        + String.join("\n", input.relevantMemories())
        + "\n\nRequested effects:\n"
        + input.requestedEffects();
  }

  private static String required(JsonNode node, String field) {
    String value = node.path(field).asText("").strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  private static List<String> strings(JsonNode node) {
    if (!node.isArray()) {
      throw new IllegalArgumentException("expected an array");
    }
    List<String> values = new ArrayList<>();
    node.forEach(value -> values.add(value.asText()));
    return List.copyOf(values);
  }

  private static String stripFence(String raw) {
    String value = Objects.requireNonNullElse(raw, "").strip();
    if (value.startsWith("```")) {
      int firstNewline = value.indexOf('\n');
      int closing = value.lastIndexOf("```");
      if (firstNewline >= 0 && closing > firstNewline) {
        return value.substring(firstNewline + 1, closing).strip();
      }
    }
    return value;
  }
}
