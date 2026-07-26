package dev.miniclaudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.miniclaudecode.domain.approval.ApprovalDecision;
import dev.miniclaudecode.domain.approval.ApprovalRequest;
import dev.miniclaudecode.domain.approval.RiskLevel;
import dev.miniclaudecode.domain.tool.ToolCall;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

class ApprovalMenuTest {

  private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

  @Test
  void mapsAllFiveChoicesToBoundApprovalDecisions() {
    ApprovalMenu menu =
        new ApprovalMenu(
            org.mockito.Mockito.mock(org.jline.reader.LineReader.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
    ApprovalRequest request = request();

    assertThat(menu.decide(request, "1", NOW).scope()).isEqualTo(ApprovalDecision.Scope.ONCE);
    assertThat(menu.decide(request, "2", NOW).scope()).isEqualTo(ApprovalDecision.Scope.TURN);
    assertThat(menu.decide(request, "3", NOW).scope()).isEqualTo(ApprovalDecision.Scope.FILE);
    assertThat(menu.decide(request, "4", NOW).scope()).isEqualTo(ApprovalDecision.Scope.PERMANENT);
    assertThat(menu.decide(request, "5", NOW).choice()).isEqualTo(ApprovalDecision.Choice.REJECT);
    assertThat(menu.decide(request, "1", NOW).approvalId()).isEqualTo(request.approvalId());
  }

  @Test
  void rejectsUnknownSelections() {
    ApprovalMenu menu =
        new ApprovalMenu(
            org.mockito.Mockito.mock(org.jline.reader.LineReader.class), Clock.systemUTC());

    assertThatThrownBy(() -> menu.decide(request(), "0", NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 to 5");
  }

  @Test
  void capturesFreeFormAnswerForAskUserInteraction() {
    LineReader reader = org.mockito.Mockito.mock(LineReader.class);
    Terminal terminal = org.mockito.Mockito.mock(Terminal.class);
    java.io.PrintWriter writer =
        new java.io.PrintWriter(java.io.OutputStream.nullOutputStream(), true);
    org.mockito.Mockito.when(reader.getTerminal()).thenReturn(terminal);
    org.mockito.Mockito.when(terminal.writer()).thenReturn(writer);
    org.mockito.Mockito.when(reader.readLine("Answer (blank to decline): "))
        .thenReturn("  agent-runtime  ");
    ApprovalMenu menu = new ApprovalMenu(reader, Clock.fixed(NOW, ZoneOffset.UTC));
    ApprovalRequest request = askUserRequest();

    ApprovalDecision decision = menu.prompt(request);

    assertThat(decision.choice()).isEqualTo(ApprovalDecision.Choice.ALLOW);
    assertThat(decision.scope()).isEqualTo(ApprovalDecision.Scope.ONCE);
    assertThat(decision.feedback()).contains("agent-runtime");
    assertThat(decision.approvalId()).isEqualTo(request.approvalId());
  }

  @Test
  void blankAnswerDeclinesAskUserInteraction() {
    LineReader reader = org.mockito.Mockito.mock(LineReader.class);
    Terminal terminal = org.mockito.Mockito.mock(Terminal.class);
    org.mockito.Mockito.when(reader.getTerminal()).thenReturn(terminal);
    org.mockito.Mockito.when(terminal.writer())
        .thenReturn(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream(), true));
    org.mockito.Mockito.when(reader.readLine("Answer (blank to decline): ")).thenReturn("  ");
    ApprovalMenu menu = new ApprovalMenu(reader, Clock.fixed(NOW, ZoneOffset.UTC));

    ApprovalDecision decision = menu.prompt(askUserRequest());

    assertThat(decision.choice()).isEqualTo(ApprovalDecision.Choice.REJECT);
    assertThat(decision.feedback()).isEmpty();
  }

  private static ApprovalRequest request() {
    return new ApprovalRequest(
        UUID.fromString("0add0d8e-4194-4eec-a419-6bf132cadc1b"),
        new ToolCall("call-1", "workspace:edit", "{}"),
        RiskLevel.MEDIUM,
        "App.java",
        "Apply diff",
        Optional.empty(),
        Optional.empty(),
        NOW);
  }

  private static ApprovalRequest askUserRequest() {
    return new ApprovalRequest(
        UUID.fromString("ec76cc9b-0af8-49ad-9346-3e915bf974b9"),
        new ToolCall("call-question", "user:ask", "{\"question\":\"Which module?\"}"),
        RiskLevel.LOW,
        "Which module?",
        "The agent needs user input",
        Optional.empty(),
        Optional.empty(),
        NOW);
  }
}
