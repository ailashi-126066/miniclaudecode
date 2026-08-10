package dev.miniclaudecode.runtime.node;

import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.message.AgentMessage.AssistantMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Completed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.Failed;
import dev.miniclaudecode.domain.model.ModelStreamEvent.TextDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ThinkingDelta;
import dev.miniclaudecode.domain.model.ModelStreamEvent.ToolCallCompleted;
import dev.miniclaudecode.domain.model.ModelStreamEvent.UsageReported;
import dev.miniclaudecode.domain.runtime.CancellationToken;
import dev.miniclaudecode.domain.runtime.CancellationToken.Registration;
import dev.miniclaudecode.domain.session.AgentStatus;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import dev.miniclaudecode.runtime.TurnLimits;
import dev.miniclaudecode.runtime.state.MiniClaudeState;
import dev.miniclaudecode.runtime.state.StateSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bsc.langgraph4j.action.AsyncNodeAction;

public final class CallModelNode implements AsyncNodeAction<MiniClaudeState> {
  private final ModelClient modelClient;
  private final TurnLimits limits;
  private final CancellationToken cancellationToken;

  public CallModelNode(ModelClient modelClient, TurnLimits limits) {
    this(modelClient, limits, null);
  }

  public CallModelNode(
      ModelClient modelClient, TurnLimits limits, CancellationToken cancellationToken) {
    this.modelClient = Objects.requireNonNull(modelClient, "modelClient must not be null");
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.cancellationToken = cancellationToken;
  }

  public CompletableFuture<Map<String, Object>> apply(MiniClaudeState state) {
    if (state.modelSteps() >= this.limits.maxModelSteps()) {
      return CompletableFuture.completedFuture(failed("model step limit exceeded"));
    } else {
      ModelRequest original = state.request();
      ModelRequest request =
          new ModelRequest(
              original.providerProfile(),
              original.modelName(),
              state.messages(),
              visibleTools(state, original),
              original.thinkingEnabled(),
              original.maxOutputTokens(),
              original.attributes());
      CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();

      try {
        this.modelClient.stream(request)
            .subscribe(new CallModelNode.ResponseSubscriber(state, result, this.cancellationToken));
      } catch (RuntimeException var6) {
        result.complete(failed("model stream failed: " + safeMessage(var6)));
      }

      return result;
    }
  }

  private static List<ToolDescriptor> visibleTools(MiniClaudeState state, ModelRequest original) {
    if (!Boolean.TRUE.equals(original.attributes().get("planningEnabled"))) {
      return original.tools();
    }
    Optional<dev.miniclaudecode.planning.PlanStep> current =
        state.plan().flatMap(dev.miniclaudecode.planning.Plan::currentStep);
    return original.tools().stream()
        .filter(
            descriptor ->
                !descriptor.effect().requiresPlan()
                    || current
                        .map(step -> step.expectedEffects().contains(descriptor.effect()))
                        .orElse(false))
        .toList();
  }

  private static Map<String, Object> failed(String message) {
    return Map.of(
        "status",
        AgentStatus.FAILED,
        "error",
        message,
        "trace",
        StateSchema.traceEntry("call_model"));
  }

  private static String safeMessage(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private static final class ResponseSubscriber implements Subscriber<ModelStreamEvent> {
    private final MiniClaudeState state;
    private final CompletableFuture<Map<String, Object>> result;
    private final List<ModelStreamEvent> events = new ArrayList<>();
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final CancellationToken cancellationToken;
    private volatile Registration cancellationRegistration;
    private volatile String failure;
    private volatile String failureType;
    private volatile boolean failureRetryable;
    private volatile UsageReported usage;

    private ResponseSubscriber(
        MiniClaudeState state,
        CompletableFuture<Map<String, Object>> result,
        CancellationToken cancellationToken) {
      this.state = state;
      this.result = result;
      this.cancellationToken = cancellationToken;
      this.metadata.putAll(state.providerMetadata());
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      if (this.cancellationToken != null) {
        this.cancellationRegistration =
            this.cancellationToken.onCancel(
                () -> {
                  subscription.cancel();
                  this.completeCancelled();
                });
      }

      if (this.terminated.get()) {
        subscription.cancel();
      } else {
        subscription.request(Long.MAX_VALUE);
      }
    }

    public void onNext(ModelStreamEvent event) {
      this.events.add(event);
      Objects.requireNonNull(event);
      switch (event) {
        case ThinkingDelta delta:
          this.thinking.append(delta.text());
          break;
        case TextDelta deltax:
          this.text.append(deltax.text());
          break;
        case ToolCallCompleted completed:
          this.toolCalls.add(completed.toolCall());
          break;
        case UsageReported reported:
          this.usage = reported;
          break;
        case Completed completedx:
          this.metadata.putAll(completedx.providerMetadata());
          break;
        case Failed failed:
          this.failureType = failed.errorType();
          this.failureRetryable = failed.retryable();
          this.failure = failed.errorType() + ": " + failed.message();
          break;
        default:
          break;
      }
    }

    @Override
    public void onError(Throwable error) {
      this.completeFailure("model publisher failed: " + CallModelNode.safeMessage(error));
    }

    @Override
    public void onComplete() {
      if (this.failure != null) {
        this.completeFailure(this.failure, this.failureType, this.failureRetryable);
      } else if (this.terminated.compareAndSet(false, true)) {
        List<AgentMessage> messages = new ArrayList<>(this.state.messages());
        messages.add(
            new AssistantMessage(
                this.text.toString(),
                Optional.of(this.thinking.toString()),
                this.toolCalls,
                this.metadata));
        Map<String, Object> update = new LinkedHashMap<>();
        if (this.usage != null) {
          this.metadata.put("inputTokens", this.usage.inputTokens());
          this.metadata.put("outputTokens", this.usage.outputTokens());
          this.metadata.put("cacheReadTokens", this.usage.cacheReadTokens());
          this.metadata.put("cacheWriteTokens", this.usage.cacheWriteTokens());
        }
        update.put("messages", List.copyOf(messages));
        update.put("modelEvents", List.copyOf(this.events));
        update.put("pendingToolCalls", List.copyOf(this.toolCalls));
        update.put("finalText", this.text.toString());
        update.put("thinking", this.thinking.toString());
        update.put("providerMetadata", Map.copyOf(this.metadata));
        update.put("modelSteps", this.state.modelSteps() + 1);
        update.put("status", AgentStatus.RUNNING);
        update.put("error", "");
        update.put("failureType", "");
        update.put("failureRetryable", false);
        update.put("retryCount", 0);
        update.put("trace", StateSchema.traceEntry("call_model"));
        this.result.complete(Map.copyOf(update));
        this.closeCancellationRegistration();
      }
    }

    private void completeFailure(String message) {
      this.completeFailure(message, "publisher_error", false);
    }

    private void completeFailure(String message, String type, boolean retryable) {
      if (this.terminated.compareAndSet(false, true)) {
        Map<String, Object> update = new LinkedHashMap<>(CallModelNode.failed(message));
        update.put("modelEvents", List.copyOf(this.events));
        update.put("modelSteps", this.state.modelSteps() + 1);
        update.put("failureType", type == null ? "model_error" : type);
        update.put("failureRetryable", retryable);
        this.result.complete(Map.copyOf(update));
        this.closeCancellationRegistration();
      }
    }

    private void completeCancelled() {
      if (this.terminated.compareAndSet(false, true)) {
        this.result.complete(
            Map.of(
                "status",
                AgentStatus.CANCELLED,
                "error",
                "turn cancelled by user",
                "trace",
                StateSchema.traceEntry("call_model_cancelled")));
        this.closeCancellationRegistration();
      }
    }

    private void closeCancellationRegistration() {
      Registration registration = this.cancellationRegistration;
      if (registration != null) {
        registration.close();
        this.cancellationRegistration = null;
      }
    }
  }
}
