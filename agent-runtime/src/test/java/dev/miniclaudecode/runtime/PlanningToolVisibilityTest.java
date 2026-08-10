package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.planning.Plan;
import dev.miniclaudecode.planning.PlanStatus;
import dev.miniclaudecode.planning.PlanStep;
import dev.miniclaudecode.planning.PlanStepStatus;
import dev.miniclaudecode.providers.FakeModelClient;
import dev.miniclaudecode.runtime.node.CallModelNode;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningToolVisibilityTest {
  private static final ToolDescriptor READ = descriptor("read", ToolEffect.READ_ONLY_LOCAL);
  private static final ToolDescriptor WRITE = descriptor("write", ToolEffect.MUTATION);
  private static final ToolDescriptor SHELL = descriptor("run", ToolEffect.PROCESS);

  @Test
  void discoveryHidesEveryToolThatRequiresAPlan() {
    FakeModelClient model = completedModel();
    new CallModelNode(model, new TurnLimits(4, 4)).apply(state(Optional.empty())).join();

    assertThat(model.requests().getFirst().tools()).containsExactly(READ);
  }

  @Test
  void activeStepExposesOnlyItsDeclaredSideEffect() {
    FakeModelClient model = completedModel();
    new CallModelNode(model, new TurnLimits(4, 4)).apply(state(Optional.of(activePlan()))).join();

    assertThat(model.requests().getFirst().tools()).containsExactly(READ, WRITE);
  }

  private static MiniClaudeState state(Optional<Plan> plan) {
    ModelRequest request =
        new ModelRequest(
            "test",
            "fake",
            List.of(new UserMessage("change a file")),
            List.of(READ, WRITE, SHELL),
            false,
            512,
            Map.of("planningEnabled", true));
    java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
    values.put(MiniClaudeState.REQUEST, request);
    values.put(MiniClaudeState.MESSAGES, request.messages());
    plan.ifPresent(value -> values.put(MiniClaudeState.PLAN, value));
    return new MiniClaudeState(values);
  }

  private static Plan activePlan() {
    Instant now = Instant.parse("2026-08-10T00:00:00Z");
    PlanStep pending =
        new PlanStep(
            "step-1",
            "write the file",
            List.of(),
            List.of("file contains the requested change"),
            Set.of(ToolEffect.MUTATION),
            PlanStepStatus.PENDING,
            0,
            Optional.empty());
    Plan active =
        new Plan(
                UUID.randomUUID(),
                "change a file",
                PlanStatus.DRAFT,
                1,
                0,
                List.of(pending),
                now,
                now)
            .activate(now);
    return active.replaceStep(pending.start(2), now);
  }

  private static FakeModelClient completedModel() {
    return FakeModelClient.scripted(
        List.of(List.of(new ModelStreamEvent.Completed("stop", Map.of()))));
  }

  private static ToolDescriptor descriptor(String name, ToolEffect effect) {
    return new ToolDescriptor("test", name, name, "{\"type\":\"object\"}", RiskLevel.LOW, effect);
  }
}
