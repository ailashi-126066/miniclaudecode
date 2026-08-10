package dev.miniclaudecode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.message.AgentMessage.UserMessage;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.domain.tool.ToolEffect;
import dev.miniclaudecode.domain.tool.ToolResult;
import dev.miniclaudecode.planning.PlanStatus;
import dev.miniclaudecode.providers.FakeModelClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PlanExecutionGraphTest {
  @Test
  void discoversCreatesPlanExecutesAndVerifiesOneStep() {
    ToolCall requestPlan =
        new ToolCall(
            "plan-1",
            "planning:request",
            "{\"goal\":\"update App.java\",\"expectedEffects\":[\"MUTATION\"]}");
    ToolCall write = new ToolCall("write-1", "workspace:write", "{\"path\":\"App.java\"}");
    String plannerJson =
        "{\"goal\":\"update App.java\",\"steps\":[{\"id\":\"step-1\","
            + "\"description\":\"update App.java\",\"dependsOn\":[],"
            + "\"acceptanceCriteria\":[\"App.java is updated\"],"
            + "\"expectedEffects\":[\"MUTATION\"]}]}";
    FakeModelClient model =
        FakeModelClient.scripted(
            List.of(
                List.of(
                    new ModelStreamEvent.ToolCallCompleted(requestPlan),
                    new ModelStreamEvent.Completed("tool_calls", Map.of())),
                List.of(
                    new ModelStreamEvent.TextDelta(plannerJson),
                    new ModelStreamEvent.Completed("stop", Map.of())),
                List.of(
                    new ModelStreamEvent.ToolCallCompleted(write),
                    new ModelStreamEvent.Completed("tool_calls", Map.of())),
                List.of(
                    new ModelStreamEvent.TextDelta(
                        "Changed Files\n- App.java\n\nVerification\n- Not run\n\nUnverified Scope\n- Project tests"),
                    new ModelStreamEvent.Completed("stop", Map.of()))));
    ToolExecutor executor =
        calls ->
            CompletableFuture.completedFuture(
                calls.stream()
                    .map(
                        call ->
                            "planning:request".equals(call.qualifiedName())
                                ? new ToolResult(
                                    call.toolCallId(),
                                    ToolResult.Status.COMPLETED,
                                    "planning requested",
                                    Optional.empty(),
                                    Map.of(
                                        "planningRequested",
                                        true,
                                        "goal",
                                        "update App.java",
                                        "expectedEffects",
                                        Set.of(ToolEffect.MUTATION)))
                                : new ToolResult(
                                    call.toolCallId(),
                                    ToolResult.Status.COMPLETED,
                                    "Applied approved change to App.java",
                                    Optional.empty(),
                                    Map.of()))
                    .toList());
    AgentGraphFactory graph = new AgentGraphFactory(model, executor, new TurnLimits(8, 8));

    var state = graph.run(request());

    assertThat(state.status()).isEqualTo(AgentStatus.COMPLETED);
    assertThat(state.plan())
        .isPresent()
        .get()
        .extracting(plan -> plan.status())
        .isEqualTo(PlanStatus.COMPLETED);
    assertThat(state.trace())
        .containsSubsequence(
            "prepare_context",
            "call_model",
            "execute_tools",
            "create_plan",
            "select_step",
            "execute_step",
            "call_model",
            "execute_tools",
            "call_model",
            "verify_step",
            "select_step",
            "final_verification",
            "finish");
  }

  private static ModelRequest request() {
    ToolDescriptor planning =
        new ToolDescriptor(
            "planning",
            "request",
            "request plan",
            "{\"type\":\"object\"}",
            RiskLevel.LOW,
            ToolEffect.READ_ONLY_LOCAL);
    ToolDescriptor write =
        new ToolDescriptor(
            "workspace",
            "write",
            "write file",
            "{\"type\":\"object\"}",
            RiskLevel.MEDIUM,
            ToolEffect.MUTATION);
    return new ModelRequest(
        "test",
        "fake",
        List.of(new UserMessage("Update App.java")),
        List.of(planning, write),
        false,
        2048,
        Map.of("planningEnabled", true, "requireVerification", false));
  }
}
